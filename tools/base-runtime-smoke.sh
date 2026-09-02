#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARCHIVE="$ROOT_DIR/app/src/main/assets/rootfs/rootfs-debian13-arm64.tgz"
CHECKSUM="$ARCHIVE.sha256"

[[ -s "$ARCHIVE" ]] || {
  echo "Missing Debian 13 rootfs: $ARCHIVE" >&2
  echo "Run tools/build-rootfs.sh first." >&2
  exit 1
}
[[ -s "$CHECKSUM" ]] || {
  echo "Missing rootfs checksum: $CHECKSUM" >&2
  exit 1
}

if command -v sha256sum >/dev/null 2>&1; then
  actual="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
else
  actual="$(shasum -a 256 "$ARCHIVE" | awk '{print $1}')"
fi
expected="$(awk 'NF { print $1; exit }' "$CHECKSUM")"
[[ "$actual" == "$expected" ]] || {
  echo "Rootfs checksum mismatch: expected $expected, got $actual" >&2
  exit 1
}

command -v docker >/dev/null 2>&1 || {
  echo "Docker is required for the arm64 VM smoke test." >&2
  exit 1
}

docker run --rm --platform linux/arm64 \
  -v "$ARCHIVE:/input/rootfs-debian13-arm64.tgz:ro" \
  debian:trixie-slim \
  bash -euc '
    rm -rf /tmp/tx5dr-rootfs
    mkdir -p /tmp/tx5dr-rootfs
    tar -xzf /input/rootfs-debian13-arm64.tgz -C /tmp/tx5dr-rootfs

    . /tmp/tx5dr-rootfs/etc/os-release
    test "$ID" = debian
    test "$VERSION_ID" = 13
    test "$VERSION_CODENAME" = trixie
    printf "rootfs=%s %s (%s)\n" "$ID" "$VERSION_ID" "$VERSION_CODENAME"

    test -x /tmp/tx5dr-rootfs/usr/bin/node
    node_version="$(chroot /tmp/tx5dr-rootfs /usr/bin/node --version)"
    printf "node=%s\n" "$node_version"
    chroot /tmp/tx5dr-rootfs /usr/bin/node -e "if (process.arch !== \"arm64\") process.exit(1); console.log(\"node-start=ok arch=\" + process.arch)"

    helper=/tmp/tx5dr-rootfs/usr/local/bin/tx5dr-android-serial-pty
    test -x "$helper"
    if ldd "$helper" | grep -q "not found"; then
      echo "serial helper has missing shared libraries" >&2
      exit 1
    fi
    printf "serial-helper=ok\n"

    for library in \
      /tmp/tx5dr-rootfs/usr/lib/aarch64-linux-gnu/libEGL.so.1 \
      /tmp/tx5dr-rootfs/usr/lib/aarch64-linux-gnu/libGLX.so.0 \
      /tmp/tx5dr-rootfs/usr/lib/aarch64-linux-gnu/libOpenGL.so.0 \
      /tmp/tx5dr-rootfs/usr/lib/aarch64-linux-gnu/libxkbcommon.so.0; do
      test -e "$library"
    done
    printf "offscreen-native-libs=ok\n"
    printf "BASE_RUNTIME_SMOKE_OK\n"
  '
