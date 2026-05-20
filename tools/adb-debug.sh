#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-adb}"
PKG="com.tx5dr.bridge"
DEBUG_ACTIVITY="$PKG/.DebugCommandActivity"
LOG_TAGS=(Tx5drBridge RuntimeManager AudioBridge UsbSerialBridge proot serial-pty)

usage() {
  cat <<USAGE
Usage: $0 <command> [args]

Commands:
  devices                 List adb devices
  install-apk             Build and install debug APK with Gradle
  launch                  Open the normal TX-5DR UI
  bootstrap               Run product bootstrap flow
  install-runtime [url]   Trigger Install/Update; optional manifest URL
  start                   Start PRoot runtime
  stop                    Stop PRoot runtime
  start-bridges           Start USB audio/serial bridges when permissions already exist
  stop-bridges            Stop USB audio/serial bridges
  keepalive-on            Enable foreground keep-alive WakeLock
  keepalive-off           Disable foreground keep-alive WakeLock
  start-usb-audio         Start Android USB audio bridge (RECORD_AUDIO must already be granted)
  stop-usb-audio          Stop Android USB audio bridge
  start-usb-serial        Start Android USB serial bridge and Linux PTY helper
  stop-usb-serial         Stop Android USB serial bridge and Linux PTY helper
  dns-smoke               Check PRoot /etc/resolv.conf, DNS lookup, and HTTPS reachability
  audio-smoke [seconds]   Inspect Android audio manifest and Unix sockets
  output-smoke [seconds]  Inspect Android audio output sockets and recent logs
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
RESOLV="/data/user/0/$PKG/files/runtime/tx5dr-data/runtime/resolv.conf"
mkdir -p "\$(dirname "\$RESOLV")" /data/user/0/$PKG/files/runtime/tx5dr-data/user/data /data/user/0/$PKG/files/runtime/tx5dr-data/user/logs /data/user/0/$PKG/files/runtime/tx5dr-data/user/plugins /data/user/0/$PKG/files/runtime/tx5dr-data/user/plugin-data
if [ ! -s "\$RESOLV" ]; then
  printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 1.1.1.1\noptions timeout:2 attempts:2\n' > "\$RESOLV"
fi
"\$NATIVE/libproot_exec.so" \\
  --rootfs=/data/user/0/$PKG/files/runtime/rootfs \\
  --pwd=/ \\
  --bind=/data/user/0/$PKG/files/runtime/tx5dr-data:/opt/tx5dr-data \\
  --bind=/data/user/0/$PKG/files/runtime/tx5dr-data/user:/opt/tx5dr-user \\
  --bind=/data/user/0/$PKG/files/runtime/tx5dr:/opt/tx5dr \\
  --bind=/data/user/0/$PKG/files/runtime/tx5dr-data/runtime/resolv.conf:/etc/resolv.conf \\
  --bind=/proc:/proc \\
  --bind=/dev:/dev \\
  --bind=/sys:/sys \\
  --kill-on-exit \\
  --link2symlink \\
  /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TX5DR_RUNTIME_FLAVOR=android-bridge TX5DR_ANDROID_AUDIO_DEVICES_FILE=/opt/tx5dr-data/runtime/android-audio-devices.json TX5DR_ANDROID_SERIAL_DEVICES_FILE=/opt/tx5dr-data/runtime/android-serial-devices.json TX5DR_DATA_DIR=/opt/tx5dr-user/data TX5DR_LOGS_DIR=/opt/tx5dr-user/logs TX5DR_PLUGINS_DIR=/opt/tx5dr-user/plugins TX5DR_PLUGIN_DATA_DIR=/opt/tx5dr-user/plugin-data TX5DR_CONFIG_DIR=/opt/tx5dr-data/config TX5DR_CACHE_DIR=/opt/tx5dr-data/cache /bin/bash -lc "$inner"
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
  bootstrap)
    run_debug_action com.tx5dr.bridge.debug.BOOTSTRAP
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
  start-bridges)
    run_debug_action com.tx5dr.bridge.debug.START_BRIDGES
    ;;
  stop-bridges)
    run_debug_action com.tx5dr.bridge.debug.STOP_BRIDGES
    ;;
  keepalive-on)
    run_debug_action com.tx5dr.bridge.debug.KEEPALIVE_ON
    ;;
  keepalive-off)
    run_debug_action com.tx5dr.bridge.debug.KEEPALIVE_OFF
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
  dns-smoke)
    tmp_inner="$(mktemp)"
    cat > "$tmp_inner" <<'INNER'
#!/bin/bash
set -e
echo RESOLV
cat /etc/resolv.conf
echo DNS_SERVERS
node -e 'const dns=require("node:dns"); console.log(dns.getServers())'
echo LOOKUP_DL
node -e 'require("node:dns").lookup("dl.tx5dr.com",(e,a,f)=>{ if(e) { console.error(e); process.exit(1); } console.log(a,f); })'
echo LOOKUP_NTP
node -e 'require("node:dns").lookup("0.pool.ntp.org",(e,a,f)=>{ if(e) { console.error(e); process.exit(1); } console.log(a,f); })'
echo HTTPS
curl -4 -m 8 -I https://dl.tx5dr.com/tx-5dr/android-runtime/nightly/latest.json | head -20
INNER
    "$ADB" push "$tmp_inner" /data/local/tmp/tx5dr-dns-smoke-inner.sh >/dev/null
    "$ADB" shell "run-as $PKG sh -c 'mkdir -p files/runtime/tx5dr-data/runtime && cat /data/local/tmp/tx5dr-dns-smoke-inner.sh > files/runtime/tx5dr-data/runtime/dns-smoke-inner.sh && chmod 700 files/runtime/tx5dr-data/runtime/dns-smoke-inner.sh'"
    rm -f "$tmp_inner"
    tmp_script="$(mktemp)"
    make_proot_script "/bin/bash /opt/tx5dr-data/runtime/dns-smoke-inner.sh" "$tmp_script"
    write_and_run_proot_script "$tmp_script"
    rm -f "$tmp_script"
    ;;
  audio-smoke)
    tmp_script="$(mktemp)"
    make_proot_script "cat /opt/tx5dr-data/runtime/android-audio-devices.json; echo; ss -lx | grep tx5dr-data || true" "$tmp_script"
    write_and_run_proot_script "$tmp_script"
    rm -f "$tmp_script"
    ;;
  output-smoke)
    tmp_script="$(mktemp)"
    make_proot_script "cat /opt/tx5dr-data/runtime/android-audio-devices.json; echo; ss -lx | grep tx5dr-data || true" "$tmp_script"
    write_and_run_proot_script "$tmp_script"
    rm -f "$tmp_script"
    echo "Recent output bridge stats:"
    "$ADB" logcat -d -v time -s "${LOG_TAGS[@]}" | grep -E "Android audio output stats|Android output backend connected|Android audio output client ended|USB audio output bridge failed" | tail -20 || true
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
