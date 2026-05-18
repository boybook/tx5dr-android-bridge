#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-adb}"
PKG="com.tx5dr.bridge"
DEBUG_ACTIVITY="$PKG/.DebugCommandActivity"
LOG_TAGS=(Tx5drBridge RuntimeManager AudioBridge proot mic-linux)

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
  start-mic               Start Android mic bridge (permission must already be granted)
  stop-mic                Stop Android mic bridge
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
  start-mic)
    run_debug_action com.tx5dr.bridge.debug.START_MIC
    ;;
  stop-mic)
    run_debug_action com.tx5dr.bridge.debug.STOP_MIC
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
