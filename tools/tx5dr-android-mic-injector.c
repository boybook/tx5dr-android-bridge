#include <arpa/inet.h>
#include <errno.h>
#include <pulse/error.h>
#include <pulse/simple.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define BUFSIZE 4096

int main(int argc, char **argv) {
  if (argc != 4) {
    fprintf(stderr, "usage: %s <host> <port> <pulse_sink>\n", argv[0]);
    return 2;
  }

  pa_sample_spec spec = { PA_SAMPLE_S16LE, 44100, 1 };
  pa_buffer_attr attr;
  memset(&attr, 0xff, sizeof(attr));
  attr.tlength = pa_usec_to_bytes(60000, &spec);

  int error = 0;
  pa_simple *pulse = pa_simple_new(NULL, "TX5DRAndroidMic", PA_STREAM_PLAYBACK, argv[3], "android-mic", &spec, NULL, &attr, &error);
  if (!pulse) {
    fprintf(stderr, "pa_simple_new failed: %s\n", pa_strerror(error));
    return 1;
  }

  int fd = socket(AF_INET, SOCK_STREAM, 0);
  if (fd < 0) { perror("socket"); return 1; }
  struct sockaddr_in addr;
  memset(&addr, 0, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_port = htons((uint16_t)atoi(argv[2]));
  if (inet_pton(AF_INET, argv[1], &addr.sin_addr) != 1) {
    fprintf(stderr, "invalid host: %s\n", argv[1]);
    return 2;
  }
  while (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
    fprintf(stderr, "waiting for Android mic stream: %s\n", strerror(errno));
    sleep(1);
  }

  uint8_t buf[BUFSIZE];
  for (;;) {
    ssize_t n = read(fd, buf, sizeof(buf));
    if (n <= 0) break;
    if (pa_simple_write(pulse, buf, (size_t)n, &error) < 0) {
      fprintf(stderr, "pa_simple_write failed: %s\n", pa_strerror(error));
      break;
    }
  }
  pa_simple_free(pulse);
  close(fd);
  return 0;
}
