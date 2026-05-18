#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <pulse/error.h>
#include <pulse/simple.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#define SAMPLE_RATE 48000
#define CHANNELS 1
#define BYTES_PER_SAMPLE 2
#define BUFFER_TARGET_MS 100
#define BUFFER_TARGET_BYTES ((SAMPLE_RATE * BUFFER_TARGET_MS / 1000) * CHANNELS * BYTES_PER_SAMPLE)

struct stream_stats {
  const char *name;
  time_t started_at;
  time_t last_logged_at;
  unsigned long long tcp_bytes;
  unsigned long long pulse_bytes;
  unsigned long long tcp_ops;
  unsigned long long pulse_ops;
  unsigned long long tcp_errors;
  unsigned long long pulse_errors;
};

static void init_stats(struct stream_stats *stats, const char *name) {
  time_t now = time(NULL);
  memset(stats, 0, sizeof(*stats));
  stats->name = name;
  stats->started_at = now;
  stats->last_logged_at = now;
}

static void maybe_log_stats(struct stream_stats *stats) {
  time_t now = time(NULL);
  if (now - stats->last_logged_at < 5) return;
  fprintf(stderr,
          "%s stats elapsed=%llds tcp_bytes=%llu pulse_bytes=%llu tcp_ops=%llu pulse_ops=%llu tcp_errors=%llu pulse_errors=%llu\n",
          stats->name,
          (long long)(now - stats->started_at),
          stats->tcp_bytes,
          stats->pulse_bytes,
          stats->tcp_ops,
          stats->pulse_ops,
          stats->tcp_errors,
          stats->pulse_errors);
  stats->last_logged_at = now;
}

static int connect_tcp(const char *host, const char *port) {
  struct addrinfo hints;
  struct addrinfo *result = NULL;
  memset(&hints, 0, sizeof(hints));
  hints.ai_family = AF_UNSPEC;
  hints.ai_socktype = SOCK_STREAM;
  int rc = getaddrinfo(host, port, &hints, &result);
  if (rc != 0) {
    fprintf(stderr, "getaddrinfo failed: %s\n", gai_strerror(rc));
    return -1;
  }
  int fd = -1;
  for (struct addrinfo *rp = result; rp; rp = rp->ai_next) {
    fd = socket(rp->ai_family, rp->ai_socktype, rp->ai_protocol);
    if (fd < 0) continue;
    if (connect(fd, rp->ai_addr, rp->ai_addrlen) == 0) break;
    close(fd);
    fd = -1;
  }
  freeaddrinfo(result);
  return fd;
}

static int connect_tcp_retry(const char *host, const char *port) {
  int fd = -1;
  for (int i = 0; i < 80; i++) {
    fd = connect_tcp(host, port);
    if (fd >= 0) return fd;
    usleep(250000);
  }
  fprintf(stderr, "failed to connect %s:%s\n", host, port);
  return -1;
}

static int run_tcp_to_sink(const char *host, const char *port, const char *sink) {
  int fd = connect_tcp_retry(host, port);
  if (fd < 0) return 2;
  pa_sample_spec spec = { .format = PA_SAMPLE_S16LE, .rate = SAMPLE_RATE, .channels = CHANNELS };
  pa_buffer_attr attr = { .maxlength = (uint32_t)-1, .tlength = BUFFER_TARGET_BYTES, .prebuf = 0, .minreq = (uint32_t)-1, .fragsize = (uint32_t)-1 };
  int error = 0;
  pa_simple *pulse = pa_simple_new(NULL, "TX5DRAndroidUsbInput", PA_STREAM_PLAYBACK, sink, "android-usb-input", &spec, NULL, &attr, &error);
  if (!pulse) {
    fprintf(stderr, "pa_simple_new playback failed: %s\n", pa_strerror(error));
    close(fd);
    return 3;
  }
  unsigned char buf[8192];
  struct stream_stats stats;
  init_stats(&stats, "tcp-to-sink");
  for (;;) {
    ssize_t n = read(fd, buf, sizeof(buf));
    if (n <= 0) {
      if (n < 0) stats.tcp_errors++;
      break;
    }
    stats.tcp_ops++;
    stats.tcp_bytes += (unsigned long long)n;
    if (pa_simple_write(pulse, buf, (size_t)n, &error) < 0) {
      stats.pulse_errors++;
      fprintf(stderr, "pa_simple_write failed: %s\n", pa_strerror(error));
      break;
    }
    stats.pulse_ops++;
    stats.pulse_bytes += (unsigned long long)n;
    maybe_log_stats(&stats);
  }
  maybe_log_stats(&stats);
  pa_simple_drain(pulse, &error);
  pa_simple_free(pulse);
  close(fd);
  return 0;
}

static int run_source_to_tcp(const char *host, const char *port, const char *source) {
  int fd = connect_tcp_retry(host, port);
  if (fd < 0) return 2;
  pa_sample_spec spec = { .format = PA_SAMPLE_S16LE, .rate = SAMPLE_RATE, .channels = CHANNELS };
  pa_buffer_attr attr = { .maxlength = (uint32_t)-1, .tlength = (uint32_t)-1, .prebuf = (uint32_t)-1, .minreq = (uint32_t)-1, .fragsize = BUFFER_TARGET_BYTES };
  int error = 0;
  pa_simple *pulse = pa_simple_new(NULL, "TX5DRAndroidUsbOutput", PA_STREAM_RECORD, source, "android-usb-output", &spec, NULL, &attr, &error);
  if (!pulse) {
    fprintf(stderr, "pa_simple_new record failed: %s\n", pa_strerror(error));
    close(fd);
    return 3;
  }
  unsigned char buf[8192];
  struct stream_stats stats;
  init_stats(&stats, "source-to-tcp");
  for (;;) {
    if (pa_simple_read(pulse, buf, sizeof(buf), &error) < 0) {
      stats.pulse_errors++;
      fprintf(stderr, "pa_simple_read failed: %s\n", pa_strerror(error));
      break;
    }
    stats.pulse_ops++;
    stats.pulse_bytes += sizeof(buf);
    ssize_t off = 0;
    while (off < (ssize_t)sizeof(buf)) {
      ssize_t n = write(fd, buf + off, sizeof(buf) - (size_t)off);
      if (n <= 0) {
        stats.tcp_errors++;
        goto done;
      }
      stats.tcp_ops++;
      stats.tcp_bytes += (unsigned long long)n;
      off += n;
    }
    maybe_log_stats(&stats);
  }
done:
  maybe_log_stats(&stats);
  pa_simple_free(pulse);
  close(fd);
  return 0;
}

int main(int argc, char **argv) {
  if (argc != 5) {
    fprintf(stderr, "usage: %s <tcp-to-sink|source-to-tcp> <host> <port> <pulse-device>\n", argv[0]);
    return 1;
  }
  if (strcmp(argv[1], "tcp-to-sink") == 0) return run_tcp_to_sink(argv[2], argv[3], argv[4]);
  if (strcmp(argv[1], "source-to-tcp") == 0) return run_source_to_tcp(argv[2], argv[3], argv[4]);
  fprintf(stderr, "unknown mode: %s\n", argv[1]);
  return 1;
}
