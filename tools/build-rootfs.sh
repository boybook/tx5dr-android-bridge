#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT_DIR/app/src/main/assets/rootfs/rootfs-debian13-arm64.tgz"
TMP="${TMPDIR:-/tmp}/tx5dr-rootfs.$$"
IMAGE="tx5dr-android-rootfs:trixie-arm64"
mkdir -p "$TMP" "$(dirname "$OUT")"
trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/Dockerfile" <<'DOCKER'
FROM --platform=linux/arm64 debian:trixie-slim
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl gnupg xz-utils zstd tar procps bash sed coreutils \
    pulseaudio pulseaudio-utils libpulse0 libpulse-dev \
    libasound2t64 libasound2-dev libjack-jackd2-0 libjack-jackd2-dev \
    libstdc++6 libgcc-s1 libgfortran5 libfftw3-single3 libfftw3-dev \
    libhamlib4 libhamlib-dev build-essential pkg-config \
  && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
  && apt-get install -y --no-install-recommends nodejs \
  && apt-get clean \
  && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*
COPY tx5dr-android-mic-injector.c /tmp/tx5dr-android-mic-injector.c
COPY tx5dr-android-pulse-tcp.c /tmp/tx5dr-android-pulse-tcp.c
COPY tx5dr-android-serial-pty.c /tmp/tx5dr-android-serial-pty.c
RUN cc -O2 -Wall -o /usr/local/bin/tx5dr-android-mic-injector /tmp/tx5dr-android-mic-injector.c -lpulse-simple -lpulse \
  && cc -O2 -Wall -o /usr/local/bin/tx5dr-android-pulse-tcp /tmp/tx5dr-android-pulse-tcp.c -lpulse-simple -lpulse \
  && cc -O2 -Wall -o /usr/local/bin/tx5dr-android-serial-pty /tmp/tx5dr-android-serial-pty.c -lutil \
  && rm /tmp/tx5dr-android-mic-injector.c /tmp/tx5dr-android-pulse-tcp.c /tmp/tx5dr-android-serial-pty.c
RUN mkdir -p /opt/tx5dr/releases /opt/tx5dr-data/config /opt/tx5dr-data/logs /opt/tx5dr-data/cache /opt/tx5dr-data/runtime
DOCKER
cp "$ROOT_DIR/tools/tx5dr-android-mic-injector.c" "$TMP/tx5dr-android-mic-injector.c"
cp "$ROOT_DIR/tools/tx5dr-android-pulse-tcp.c" "$TMP/tx5dr-android-pulse-tcp.c"
cp "$ROOT_DIR/tools/tx5dr-android-serial-pty.c" "$TMP/tx5dr-android-serial-pty.c"
docker buildx build --platform linux/arm64 --load -t "$IMAGE" "$TMP"
CID="$(docker create --platform linux/arm64 "$IMAGE")"
docker export "$CID" | gzip -9 > "$OUT"
docker rm "$CID" >/dev/null
shasum -a 256 "$OUT" | tee "$OUT.sha256"
ls -lh "$OUT"
