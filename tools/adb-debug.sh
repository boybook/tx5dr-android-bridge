#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-adb}"
PKG="com.tx5dr.bridge"
DEBUG_ACTIVITY="$PKG/.DebugCommandActivity"
LOG_TAGS=(Tx5drBridge RuntimeManager AudioBridge UsbSerialBridge proot mic-linux serial-pty)

usage() {
  cat <<USAGE
Usage: $0 <command> [args]

Commands:
  devices                 List adb devices
  install-apk             Build and install debug APK with Gradle
  launch                  Open the normal TX-5DR UI
  install-runtime [url]   Trigger Install/Update; optional manifest URL
  start                   Start PRoot runtime
  stop                    Stop PRoot runtime
  start-usb-audio         Start Android USB audio bridge (RECORD_AUDIO must already be granted)
  stop-usb-audio          Stop Android USB audio bridge
  start-usb-serial        Start Android USB serial bridge and Linux PTY helper
  stop-usb-serial         Stop Android USB serial bridge and Linux PTY helper
  audio-smoke [seconds]   Record TX5DRAndroidUsbInput inside PRoot and print byte/nonzero stats
  output-smoke [seconds]  Play a 48kHz mono tone into TX5DRAndroidOutput and print recent output stats
  start-mic               Alias for start-usb-audio
  stop-mic                Alias for stop-usb-audio
  set-manifest <url>      Save manifest URL without installing
  logs                    Follow filtered logcat
  logs-once               Print current filtered logcat buffer
  clear-logs              Clear logcat buffer
  status                  Emit a debug status marker to logcat
USAGE
}

run_debug_action() {
  local action="$1"
  shift || true
  "$ADB" shell am start -W -n "$DEBUG_ACTIVITY" -a "$action" "$@" >/dev/null
}

write_and_run_proot_script() {
  local local_script="$1"
  "$ADB" push "$local_script" /data/local/tmp/tx5dr-bridge-debug.sh >/dev/null
  "$ADB" shell "run-as $PKG sh -c 'mkdir -p files/runtime && cat /data/local/tmp/tx5dr-bridge-debug.sh > files/runtime/debug.sh && chmod 700 files/runtime/debug.sh && files/runtime/debug.sh'"
}

make_proot_script() {
  local inner="$1"
  local script="$2"
  local apk native_dir
  apk="$("$ADB" shell pm path "$PKG" | tr -d '\r' | sed 's/package://')"
  native_dir="$(dirname "$apk")/lib/arm64"
  cat > "$script" <<SCRIPT
#!/system/bin/sh
set -eu
NATIVE="$native_dir"
export LD_LIBRARY_PATH="/data/user/0/$PKG/files/runtime/host-libs:\$NATIVE"
export PROOT_LOADER="\$NATIVE/libproot_loader.so"
export PROOT_LOADER_32="\$NATIVE/libproot_loader32.so"
export PROOT_TMP_DIR="/data/user/0/$PKG/files/runtime/proot-tmp"
"\$NATIVE/libproot_exec.so" \\
  --rootfs=/data/user/0/$PKG/files/runtime/rootfs \\
  --pwd=/ \\
  --bind=/data/user/0/$PKG/files/runtime/tx5dr-data:/opt/tx5dr-data \\
  --bind=/data/user/0/$PKG/files/runtime/tx5dr:/opt/tx5dr \\
  --bind=/proc:/proc \\
  --bind=/dev:/dev \\
  --bind=/sys:/sys \\
  --kill-on-exit \\
  --link2symlink \\
  /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin PULSE_SERVER=tcp:127.0.0.1:4718 /bin/bash -lc "$inner"
SCRIPT
}

case "${1:-}" in
  devices)
    "$ADB" devices -l
    ;;
  install-apk)
    (cd "$ROOT_DIR" && ./gradlew installDebug)
    ;;
  launch)
    "$ADB" shell am start -n "$PKG/.MainActivity"
    ;;
  install-runtime)
    if [[ -n "${2:-}" ]]; then
      run_debug_action com.tx5dr.bridge.debug.INSTALL --es manifest_url "$2"
    else
      run_debug_action com.tx5dr.bridge.debug.INSTALL
    fi
    ;;
  start)
    run_debug_action com.tx5dr.bridge.debug.START
    ;;
  stop)
    run_debug_action com.tx5dr.bridge.debug.STOP
    ;;
  start-usb-audio|start-mic)
    run_debug_action com.tx5dr.bridge.debug.START_MIC
    ;;
  stop-usb-audio|stop-mic)
    run_debug_action com.tx5dr.bridge.debug.STOP_MIC
    ;;
  start-usb-serial)
    run_debug_action com.tx5dr.bridge.debug.START_USB_SERIAL
    ;;
  stop-usb-serial)
    run_debug_action com.tx5dr.bridge.debug.STOP_USB_SERIAL
    ;;
  audio-smoke)
    seconds="${2:-3}"
    tmp_inner="$(mktemp)"
    cat > "$tmp_inner" <<'INNER'
#!/bin/bash
set -e
seconds="${1:-3}"
echo SOURCES
pactl list short sources
echo RECORD
set +e
timeout "$seconds" parec -d TX5DRAndroidUsbInput --raw --rate=48000 --format=s16le --channels=1 > /tmp/android-usb-input.raw
rc=$?
set -e
if [ "$rc" -ne 0 ] && [ "$rc" -ne 124 ]; then
  exit "$rc"
fi
wc -c /tmp/android-usb-input.raw
ls -lh /tmp/android-usb-input.raw
INNER
    "$ADB" push "$tmp_inner" /data/local/tmp/tx5dr-audio-smoke-inner.sh >/dev/null
    "$ADB" shell "run-as $PKG sh -c 'mkdir -p files/runtime/tx5dr-data/runtime && cat /data/local/tmp/tx5dr-audio-smoke-inner.sh > files/runtime/tx5dr-data/runtime/audio-smoke-inner.sh && chmod 700 files/runtime/tx5dr-data/runtime/audio-smoke-inner.sh'"
    rm -f "$tmp_inner"
    tmp_script="$(mktemp)"
    make_proot_script "/bin/bash /opt/tx5dr-data/runtime/audio-smoke-inner.sh $seconds" "$tmp_script"
    write_and_run_proot_script "$tmp_script"
    rm -f "$tmp_script"
    tmp_raw="$(mktemp)"
    "$ADB" exec-out run-as "$PKG" cat files/runtime/rootfs/tmp/android-usb-input.raw > "$tmp_raw"
    python3 - "$tmp_raw" "$seconds" <<'PY'
import struct
import sys
from pathlib import Path
path = Path(sys.argv[1])
seconds = float(sys.argv[2])
data = path.read_bytes()
samples = struct.unpack('<' + 'h' * (len(data) // 2), data[:len(data) // 2 * 2])
nonzero = sum(1 for sample in samples if sample)
peak = max((abs(sample) for sample in samples), default=0)
expected = int(seconds * 48000 * 2)
ratio = (len(data) / expected) if expected else 0
window_samples = 4800
silent_windows = 0
low_windows = 0
total_windows = 0
for start in range(0, len(samples), window_samples):
    window = samples[start:start + window_samples]
    if not window:
        continue
    total_windows += 1
    window_nonzero = sum(1 for sample in window if sample)
    window_peak = max((abs(sample) for sample in window), default=0)
    if window_nonzero == 0:
        silent_windows += 1
    elif window_peak < 8:
        low_windows += 1
print(
    "PCM stats: "
    f"bytes={len(data)} expected={expected} ratio={ratio:.3f} "
    f"samples={len(samples)} nonzero={nonzero} peak={peak} "
    f"windows100ms={total_windows} silentWindows={silent_windows} lowPeakWindows={low_windows}"
)
PY
    rm -f "$tmp_raw"
    ;;
  output-smoke)
    seconds="${2:-3}"
    tmp_inner="$(mktemp)"
    cat > "$tmp_inner" <<'INNER'
#!/bin/bash
set -e
seconds="${1:-3}"
echo SINKS
pactl list short sinks
echo TONE
python3 - "$seconds" > /tmp/android-output-tone.raw <<'PY'
import math
import struct
import sys

seconds = float(sys.argv[1])
rate = 48000
freq = 1000.0
amp = 12000
samples = int(seconds * rate)
for i in range(samples):
    sample = int(math.sin(2.0 * math.pi * freq * i / rate) * amp)
    sys.stdout.buffer.write(struct.pack("<h", sample))
PY
wc -c /tmp/android-output-tone.raw
echo PLAY
pacat --raw --rate=48000 --format=s16le --channels=1 -d TX5DRAndroidOutput /tmp/android-output-tone.raw
INNER
    "$ADB" push "$tmp_inner" /data/local/tmp/tx5dr-output-smoke-inner.sh >/dev/null
    "$ADB" shell "run-as $PKG sh -c 'mkdir -p files/runtime/tx5dr-data/runtime && cat /data/local/tmp/tx5dr-output-smoke-inner.sh > files/runtime/tx5dr-data/runtime/output-smoke-inner.sh && chmod 700 files/runtime/tx5dr-data/runtime/output-smoke-inner.sh'"
    rm -f "$tmp_inner"
    tmp_script="$(mktemp)"
    make_proot_script "/bin/bash /opt/tx5dr-data/runtime/output-smoke-inner.sh $seconds" "$tmp_script"
    write_and_run_proot_script "$tmp_script"
    rm -f "$tmp_script"
    echo "Recent output bridge stats:"
    "$ADB" logcat -d -v time -s "${LOG_TAGS[@]}" | grep -E "USB audio output stats|Linux Pulse output capture connected|USB audio output bridge failed" | tail -20 || true
    ;;
  set-manifest)
    [[ -n "${2:-}" ]] || { echo "set-manifest requires a URL" >&2; exit 2; }
    run_debug_action com.tx5dr.bridge.debug.STATUS --es manifest_url "$2"
    ;;
  logs)
    "$ADB" logcat -v time -s "${LOG_TAGS[@]}"
    ;;
  logs-once)
    "$ADB" logcat -d -v time -s "${LOG_TAGS[@]}"
    ;;
  clear-logs)
    "$ADB" logcat -c
    ;;
  status)
    run_debug_action com.tx5dr.bridge.debug.STATUS
    ;;
  -h|--help|help|"")
    usage
    ;;
  *)
    echo "Unknown command: $1" >&2
    usage >&2
    exit 2
    ;;
esac
