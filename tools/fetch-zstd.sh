#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT_DIR/app/src/main/assets/proot/zstd-arm64"
JNI_OUT="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a/libzstd_exec.so"
DEFAULT_ZSTD_URLS=(
  "https://mirrors.sau.edu.cn/termux/apt/termux-main/pool/main/z/zstd/zstd_1.5.7-1_aarch64.deb"
  "https://packages-cf.termux.dev/apt/termux-main/pool/main/z/zstd/zstd_1.5.7-1_aarch64.deb"
  "https://packages.termux.dev/apt/termux-main/pool/main/z/zstd/zstd_1.5.7-1_aarch64.deb"
)
URLS=("${ZSTD_DEB_URL:-${DEFAULT_ZSTD_URLS[0]}}")
if [[ -z "${ZSTD_DEB_URL:-}" ]]; then URLS=("${DEFAULT_ZSTD_URLS[@]}"); fi
TMP="${TMPDIR:-/tmp}/tx5dr-zstd.$$"
mkdir -p "$TMP" "$(dirname "$OUT")" "$(dirname "$JNI_OUT")"
trap 'rm -rf "$TMP"' EXIT
downloaded=0
for URL in "${URLS[@]}"; do
  echo "Trying $URL"
  if curl -fL "$URL" -o "$TMP/zstd.deb"; then downloaded=1; break; fi
done
if [[ "$downloaded" != "1" ]]; then
  echo "Failed to download zstd. Set ZSTD_DEB_URL to a reachable aarch64 zstd .deb." >&2
  exit 1
fi
(cd "$TMP" && tar -xf zstd.deb)
DATA="$(find "$TMP" -maxdepth 1 -name 'data.tar.*' | head -1)"
[ -n "$DATA" ] || { echo "data.tar.* not found in deb" >&2; exit 1; }
mkdir -p "$TMP/data"
tar -xf "$DATA" -C "$TMP/data"
cp "$TMP/data/data/data/com.termux/files/usr/bin/zstd" "$OUT" 2>/dev/null \
  || cp "$TMP/data/usr/bin/zstd" "$OUT" 2>/dev/null \
  || cp "$(find "$TMP/data" -type f -name zstd | head -1)" "$OUT"
chmod 0755 "$OUT"
cp "$OUT" "$JNI_OUT"
chmod 0755 "$JNI_OUT"
LIB_SRC="$(find "$TMP/data" -type f -name 'libzstd.so.1.*' | head -1)"
if [[ -z "$LIB_SRC" ]]; then
  echo "Failed to locate libzstd.so.1 dependency in zstd package" >&2
  exit 1
fi
cp "$LIB_SRC" "$(dirname "$JNI_OUT")/libzstd.so"
chmod 0755 "$(dirname "$JNI_OUT")/libzstd.so"
shasum -a 256 "$OUT" | awk '{print $1 "  zstd-arm64"}' | tee "$OUT.sha256"
shasum -a 256 "$(dirname "$JNI_OUT")/libzstd.so" | awk '{print $1 "  libzstd.so"}' | tee "$(dirname "$OUT")/libzstd.so.sha256"
echo "Fetched zstd to $OUT"
echo "Fetched libzstd.so to $(dirname "$JNI_OUT")/libzstd.so"
