#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT_DIR/app/src/main/assets/proot/proot-arm64"
JNI_OUT="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a/libproot_exec.so"
JNI_DIR="$(dirname "$JNI_OUT")"
DEFAULT_PROOT_URLS=(
  "https://mirrors.sau.edu.cn/termux/apt/termux-main/pool/main/p/proot/proot_5.1.107-70_aarch64.deb"
  "https://packages-cf.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107-70_aarch64.deb"
  "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107-70_aarch64.deb"
)
DEFAULT_LIBTALLOC_URLS=(
  "https://mirrors.sau.edu.cn/termux/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
  "https://packages-cf.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
  "https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
)
URLS=("${PROOT_DEB_URL:-${DEFAULT_PROOT_URLS[0]}}")
if [[ -z "${PROOT_DEB_URL:-}" ]]; then
  URLS=("${DEFAULT_PROOT_URLS[@]}")
fi
TMP="${TMPDIR:-/tmp}/tx5dr-proot.$$"
mkdir -p "$TMP" "$(dirname "$OUT")" "$JNI_DIR"
trap 'rm -rf "$TMP"' EXIT

extract_deb_data() {
  local deb="$1"
  local out="$2"
  local ar_dir="$out/ar"
  local data_archive
  rm -rf "$out"
  mkdir -p "$ar_dir" "$out/data"
  if command -v dpkg-deb >/dev/null 2>&1; then
    dpkg-deb -x "$deb" "$out/data"
    return
  elif command -v bsdtar >/dev/null 2>&1; then
    bsdtar -xf "$deb" -C "$ar_dir"
  elif command -v ar >/dev/null 2>&1; then
    (cd "$ar_dir" && ar -x "$deb")
  else
    echo "Need dpkg-deb, bsdtar, or ar to extract Debian package: $deb" >&2
    exit 1
  fi
  data_archive="$(find "$ar_dir" -maxdepth 1 -name 'data.tar.*' | head -1)"
  [ -n "$data_archive" ] || { echo "data.tar.* not found in $deb" >&2; exit 1; }
  tar -xf "$data_archive" -C "$out/data"
}

downloaded=0
for URL in "${URLS[@]}"; do
  echo "Trying $URL"
  if curl -fL "$URL" -o "$TMP/proot.deb"; then
    downloaded=1
    break
  fi
done
if [[ "$downloaded" != "1" ]]; then
  echo "Failed to download proot. Set PROOT_DEB_URL to a reachable aarch64 proot .deb." >&2
  exit 1
fi
extract_deb_data "$TMP/proot.deb" "$TMP/proot"
PROOT_DATA="$TMP/proot/data"
cp "$PROOT_DATA/data/data/com.termux/files/usr/bin/proot" "$OUT" 2>/dev/null \
  || cp "$PROOT_DATA/usr/bin/proot" "$OUT" 2>/dev/null \
  || cp "$(find "$PROOT_DATA" -type f -name proot | head -1)" "$OUT"
chmod 0755 "$OUT"
cp "$OUT" "$JNI_OUT"
chmod 0755 "$JNI_OUT"

cp "$PROOT_DATA/data/data/com.termux/files/usr/libexec/proot/loader" "$JNI_DIR/libproot_loader.so" 2>/dev/null \
  || cp "$PROOT_DATA/usr/libexec/proot/loader" "$JNI_DIR/libproot_loader.so" 2>/dev/null \
  || cp "$(find "$PROOT_DATA" -type f -path '*/libexec/proot/loader' | head -1)" "$JNI_DIR/libproot_loader.so"
cp "$PROOT_DATA/data/data/com.termux/files/usr/libexec/proot/loader32" "$JNI_DIR/libproot_loader32.so" 2>/dev/null \
  || cp "$PROOT_DATA/usr/libexec/proot/loader32" "$JNI_DIR/libproot_loader32.so" 2>/dev/null \
  || cp "$(find "$PROOT_DATA" -type f -path '*/libexec/proot/loader32' | head -1)" "$JNI_DIR/libproot_loader32.so"
chmod 0755 "$JNI_DIR/libproot_loader.so" "$JNI_DIR/libproot_loader32.so"

TALLOC_URLS=("${LIBTALLOC_DEB_URL:-${DEFAULT_LIBTALLOC_URLS[0]}}")
if [[ -z "${LIBTALLOC_DEB_URL:-}" ]]; then
  TALLOC_URLS=("${DEFAULT_LIBTALLOC_URLS[@]}")
fi
downloaded=0
for URL in "${TALLOC_URLS[@]}"; do
  echo "Trying $URL"
  if curl -fL "$URL" -o "$TMP/libtalloc.deb"; then
    downloaded=1
    break
  fi
done
if [[ "$downloaded" != "1" ]]; then
  echo "Failed to download libtalloc. Set LIBTALLOC_DEB_URL to a reachable aarch64 libtalloc .deb." >&2
  exit 1
fi
extract_deb_data "$TMP/libtalloc.deb" "$TMP/talloc"
TALLOC_SRC="$(find "$TMP/talloc/data" -type f -name 'libtalloc.so*' | head -1)"
[ -n "$TALLOC_SRC" ] || { echo "libtalloc.so not found in deb" >&2; exit 1; }
# Gradle only packages native libraries named lib*.so, so the versioned SONAME
# is recreated at runtime as libtalloc.so.2 -> nativeLibraryDir/libtalloc.so.
cp "$TALLOC_SRC" "$JNI_DIR/libtalloc.so"
chmod 0755 "$JNI_DIR/libtalloc.so"

shasum -a 256 "$OUT" | awk '{print $1 "  proot-arm64"}' | tee "$ROOT_DIR/app/src/main/assets/proot/proot-arm64.sha256"
shasum -a 256 "$JNI_DIR/libproot_loader.so" | awk '{print $1 "  libproot_loader.so"}' | tee "$ROOT_DIR/app/src/main/assets/proot/proot-loader-arm64.sha256"
shasum -a 256 "$JNI_DIR/libproot_loader32.so" | awk '{print $1 "  libproot_loader32.so"}' | tee "$ROOT_DIR/app/src/main/assets/proot/proot-loader32-arm.sha256"
shasum -a 256 "$JNI_DIR/libtalloc.so" | awk '{print $1 "  libtalloc.so"}' | tee "$ROOT_DIR/app/src/main/assets/proot/libtalloc.so.sha256"
echo "Fetched proot to $OUT"
echo "Fetched proot native dependencies to $JNI_DIR"
