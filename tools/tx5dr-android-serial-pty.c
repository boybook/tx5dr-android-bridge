#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define FRAME_DATA 1
#define FRAME_CONFIG 2
#define FRAME_CONTROL 3
#define FRAME_STATUS 4

static volatile sig_atomic_t running = 1;
static void on_signal(int sig) { (void)sig; running = 0; }

static int connect_unix_socket(const char *path) {
  int fd = socket(AF_UNIX, SOCK_STREAM, 0);
  if (fd < 0) return -1;
  struct sockaddr_un addr;
  memset(&addr, 0, sizeof(addr));
  addr.sun_family = AF_UNIX;
  if (strlen(path) >= sizeof(addr.sun_path)) {
    close(fd);
    errno = ENAMETOOLONG;
    return -1;
  }
  strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
  if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) == 0) return fd;
  close(fd);
  return -1;
}

static int write_all(int fd, const void *buf, size_t len) {
  const unsigned char *p = (const unsigned char *)buf;
  while (len > 0) {
    ssize_t n = write(fd, p, len);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) return -1;
    p += n;
    len -= (size_t)n;
  }
  return 0;
}

static void warn_throttled(const char *message) {
  static time_t last_warn = 0;
  time_t now = time(NULL);
  if (now - last_warn >= 30) {
    fprintf(stderr, "%s\n", message);
    last_warn = now;
  }
}

static void sleep_after_pty_idle(void) { usleep(50000); }

static int set_nonblocking(int fd) {
  int flags = fcntl(fd, F_GETFL, 0);
  if (flags < 0) return -1;
  return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static int write_pty_best_effort(int fd, const void *buf, size_t len) {
  const unsigned char *p = (const unsigned char *)buf;
  while (len > 0) {
    ssize_t n = write(fd, p, len);
    if (n < 0 && errno == EINTR) continue;
    if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
      warn_throttled("PTY output buffer full; dropping serial chunk");
      return 0;
    }
    if (n < 0 && errno == EIO) {
      warn_throttled("PTY slave is not ready; dropping serial chunk");
      return 0;
    }
    if (n <= 0) {
      warn_throttled("PTY write returned no progress; dropping serial chunk");
      return 0;
    }
    p += n;
    len -= (size_t)n;
  }
  return 0;
}

static int read_all(int fd, void *buf, size_t len) {
  unsigned char *p = (unsigned char *)buf;
  while (len > 0) {
    ssize_t n = read(fd, p, len);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) return -1;
    p += n;
    len -= (size_t)n;
  }
  return 0;
}

static int send_frame(int fd, uint8_t type, const void *payload, uint32_t len) {
  uint32_t be_len = htonl(len);
  if (write_all(fd, &type, 1) < 0) return -1;
  if (write_all(fd, &be_len, 4) < 0) return -1;
  if (len > 0 && write_all(fd, payload, len) < 0) return -1;
  return 0;
}

static int baud_to_int(speed_t speed) {
  switch (speed) {
    case B1200: return 1200;
    case B2400: return 2400;
    case B4800: return 4800;
    case B9600: return 9600;
    case B19200: return 19200;
    case B38400: return 38400;
    case B57600: return 57600;
    case B115200: return 115200;
    case B230400: return 230400;
#ifdef B460800
    case B460800: return 460800;
#endif
    default: return 9600;
  }
}

static void maybe_send_config(int sock, int pty, struct termios *last, int *have_last) {
  struct termios t;
  if (tcgetattr(pty, &t) != 0) return;
  if (*have_last && memcmp(&t, last, sizeof(t)) == 0) return;
  *last = t;
  *have_last = 1;
  int data_bits = 8;
  switch (t.c_cflag & CSIZE) {
    case CS5: data_bits = 5; break;
    case CS6: data_bits = 6; break;
    case CS7: data_bits = 7; break;
    default: data_bits = 8; break;
  }
  const char *parity = "none";
  if (t.c_cflag & PARENB) parity = (t.c_cflag & PARODD) ? "odd" : "even";
  int stop_bits = (t.c_cflag & CSTOPB) ? 2 : 1;
  char json[160];
  int n = snprintf(json, sizeof(json), "{\"baud\":%d,\"dataBits\":%d,\"stopBits\":%d,\"parity\":\"%s\"}", baud_to_int(cfgetospeed(&t)), data_bits, stop_bits, parity);
  if (n > 0) send_frame(sock, FRAME_CONFIG, json, (uint32_t)n);
}

int main(int argc, char **argv) {
  if (argc != 3) {
    fprintf(stderr, "usage: %s <symlink-path> <socket-path>\n", argv[0]);
    return 1;
  }
  signal(SIGTERM, on_signal);
  signal(SIGINT, on_signal);
  int master = -1;
  int slave = -1;
  char slave_name[256];
  if (openpty(&master, &slave, slave_name, NULL, NULL) != 0) {
    perror("openpty");
    return 2;
  }
  if (set_nonblocking(master) != 0) {
    perror("set_nonblocking(master)");
    close(master);
    close(slave);
    return 4;
  }
  unlink(argv[1]);
  if (symlink(slave_name, argv[1]) != 0) {
    perror("symlink");
    close(master);
    close(slave);
    return 3;
  }
  fprintf(stderr, "serial PTY ready: %s -> %s\n", argv[1], slave_name);

  int sock = -1;
  for (int i = 0; i < 80 && running; i++) {
    sock = connect_unix_socket(argv[2]);
    if (sock >= 0) break;
    usleep(250000);
  }
  if (sock < 0) {
    fprintf(stderr, "failed to connect serial bridge socket: %s\n", argv[2]);
    close(master);
    close(slave);
    unlink(argv[1]);
    return 5;
  }
  send_frame(sock, FRAME_STATUS, "connected", 9);

  struct termios last;
  int have_last = 0;
  unsigned char buf[4096];
  while (running) {
    maybe_send_config(sock, master, &last, &have_last);
    fd_set rfds;
    FD_ZERO(&rfds);
    FD_SET(master, &rfds);
    FD_SET(sock, &rfds);
    int maxfd = master > sock ? master : sock;
    struct timeval tv = { .tv_sec = 0, .tv_usec = 200000 };
    int rc = select(maxfd + 1, &rfds, NULL, NULL, &tv);
    if (rc < 0 && errno == EINTR) continue;
    if (rc < 0) break;
    if (FD_ISSET(master, &rfds)) {
      ssize_t n = read(master, buf, sizeof(buf));
      if (n > 0) {
        if (send_frame(sock, FRAME_DATA, buf, (uint32_t)n) < 0) break;
      } else if (n < 0 && errno == EINTR) {
        continue;
      } else if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        continue;
      } else if (n < 0 && errno == EIO) {
        sleep_after_pty_idle();
        continue;
      } else if (n == 0) {
        sleep_after_pty_idle();
        continue;
      } else {
        break;
      }
    }
    if (FD_ISSET(sock, &rfds)) {
      uint8_t type;
      uint32_t be_len;
      if (read_all(sock, &type, 1) < 0 || read_all(sock, &be_len, 4) < 0) break;
      uint32_t len = ntohl(be_len);
      if (len > sizeof(buf)) break;
      if (read_all(sock, buf, len) < 0) break;
      if (type == FRAME_DATA && len > 0) {
        if (write_pty_best_effort(master, buf, len) < 0) break;
      }
    }
  }
  close(sock);
  close(master);
  close(slave);
  unlink(argv[1]);
  return 0;
}
