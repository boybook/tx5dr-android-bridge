# TX-5DR Android

TX-5DR Android turns an Android 9+ arm64 phone or tablet into a portable TX-5DR radio gateway. The app runs the TX-5DR Linux runtime inside an app-private Debian PRoot environment, opens the TX-5DR web interface in an embedded WebView, and bridges Android audio, USB serial, notifications, and LAN/hotspot access into the runtime.

- App name: `TX-5DR`
- Package id: `com.tx5dr.bridge`
- Supported devices: real arm64 Android 9+ devices (`minSdk 28`)
- Distribution channel: signed nightly APK from the TX-5DR download site
- Runtime channel: Android arm64 TX-5DR runtime manifest downloaded by the app

This repository contains the Android shell and bridge layer only. The TX-5DR server/web runtime is published separately and installed or updated by the app from the runtime manifest.

## What It Provides

- Productized Material 3 Android shell with light/dark TX-5DR rose theme.
- First-run runtime install/update flow with sha256 verification and recoverable failures.
- Embedded WebView entrypoint for `http://127.0.0.1:8076` with local admin-token injection.
- LAN and phone-hotspot access through `client-tools` on port `8076`.
- Android WebView notification bridge for TX-5DR system reminders.
- Android audio device bridge that exposes all supported Android inputs/outputs to TX-5DR.
- USB serial bridge for radio CAT/PTT through a Linux PTY visible inside PRoot.
- Foreground service, optional keep-alive wake lock, diagnostics logs, and ADB debug helpers.

## Download

Nightly signed APK metadata is published at:

```text
https://dl.tx5dr.com/tx-5dr/android-bridge/nightly/latest.json
```

The APK manifest is separate from the TX-5DR runtime manifest consumed by the app:

```text
APK:     https://dl.tx5dr.com/tx-5dr/android-bridge/nightly/latest.json
Runtime: https://dl.tx5dr.com/tx-5dr/android-runtime/nightly/latest.json
```

Nightly builds are intended for field testing. They are signed and installable, but they are not Play Store releases.

## User Flow

1. Install and open the APK.
2. Grant microphone, notification, USB audio, or USB serial permissions only when the app asks for the relevant feature.
3. Install or update the TX-5DR Android runtime when prompted.
4. Start the service. When health checks pass, tap **Enter TX-5DR** or allow the app to open it automatically.
5. Use the displayed LAN/hotspot URL from another device on the same network, or keep using the embedded WebView.
6. Optional: enable keep-alive mode for long-running foreground operation while the screen is off.

## Architecture

```text
Android app (Kotlin + Compose Material 3)
  MainActivity
    - product dashboard
    - settings and runtime logs
    - native WebView overlay for TX-5DR
    - Android notification JavaScript bridge
  BridgeService
    - foreground service and keep-alive wake lock
  BridgeRuntime
    - Debian rootfs extraction
    - TX-5DR runtime download, verification, and switching
    - PRoot process lifecycle
    - health checks for :4000 and :8076
  NetworkAccessProvider
    - hotspot/LAN IPv4 discovery
    - PRoot resolv.conf generation from Android DNS
    - runtime/android-network-access.json for Node services
  AndroidUsbAudioBridge
    - Android AudioRecord/AudioTrack Unix socket endpoints
    - latest-frame PCM queues to avoid accumulated latency
    - Unix socket PCM streams under runtime/tx5dr-data/runtime/sockets/
    - runtime/android-audio-devices.json for TX-5DR device enumeration
  AndroidUsbSerialBridge
    - USB Host API permission and device discovery
    - Unix socket framed serial bridges under runtime/tx5dr-data/runtime/sockets/

App private files directory
  runtime/rootfs/                 Debian 13 minbase rootfs
  runtime/tx5dr/releases/<ver>/   TX-5DR portable runtime releases
  runtime/tx5dr/current           symlink to active release
  runtime/tx5dr-data/             persistent TX-5DR data/config/log/cache
  runtime/tx5dr-data/android-dev/ virtual serial symlinks visible to Hamlib
  runtime/tx5dr-data/runtime/     manifests, DNS, and Unix socket directory
  runtime/host-libs/              Android-side native library aliases
  runtime/proot-tmp/              PRoot temp directory

PRoot Debian runtime
  /opt/tx5dr/current              active TX-5DR release
  /opt/tx5dr-data                 persistent data bind mount
  /etc/resolv.conf                Android-generated DNS bind mount
  127.0.0.1:4000                  TX-5DR server
  0.0.0.0:8076                    client-tools LAN web proxy/static server
  /opt/tx5dr-data/runtime/sockets Android audio and serial bridge sockets
```

LAN browser access uses `client-tools` as the only public entrypoint:

```text
LAN browser
  -> http://phone-lan-ip:8076
     client-tools in PRoot, HOST=0.0.0.0
       -> http://127.0.0.1:4000
          tx5dr-server in PRoot, TX5DR_SERVER_HOST=127.0.0.1
```

The Debian/Node side does not enumerate Android network interfaces. Kotlin writes `/opt/tx5dr-data/runtime/android-network-access.json`, and TX-5DR consumes it through `TX5DR_NETWORK_ACCESS_FILE`. Kotlin also writes `/opt/tx5dr-data/runtime/resolv.conf` from Android DNS servers and bind-mounts it to `/etc/resolv.conf`, so runtime features such as updates, plugin marketplace access, and NTP hostname lookup work inside PRoot.

## Runtime Logic

1. Install/update first ensures the embedded Debian rootfs is extracted to `files/runtime/rootfs`.
2. The app downloads the configured runtime manifest, selects the `android` + `arm64` tarball, checks `sha256`, then extracts it to `files/runtime/tx5dr/releases/<version>`.
3. `files/runtime/tx5dr/current` is switched to the newly installed release. The symlink is relative (`releases/<version>`) so it remains visible after PRoot bind mounts `files/runtime/tx5dr` as `/opt/tx5dr`.
4. Startup prepares Android-side native host libraries, refreshes LAN/DNS/serial state files, then launches PRoot with bind mounts for `/opt/tx5dr`, `/opt/tx5dr-data`, `/etc/resolv.conf`, `/proc`, `/dev`, and `/sys`.
5. Inside Debian, the startup script starts PulseAudio, starts the TX-5DR server on loopback, starts `client-tools` on `0.0.0.0:8076`, and keeps the PRoot process alive until either child exits or Android sends stop.
6. Android starts the serial PTY helper as a separate supervised PRoot process so it can be debugged and restarted independently.
7. The Android side periodically health-checks `GET http://127.0.0.1:4000/api/hello` and `GET http://127.0.0.1:8076/`; LAN reachability is shown separately and does not affect local health.

The Android runtime does not use `systemd` or `nginx`. Static web serving and API proxying are handled by `packages/client-tools/src/proxy.js`, matching the Electron-oriented client-tools path.

## Build From Source

A debug APK can be compiled without large runtime assets, but install/start will fail until the PRoot, zstd, and rootfs assets exist.

```bash
cd /Users/fangyizhou/Documents/coding/tx5dr-android-bridge
./tools/fetch-proot.sh
./tools/fetch-zstd.sh
./tools/build-rootfs.sh
./gradlew assembleDebug
```

`tools/build-rootfs.sh` requires Docker with `linux/arm64` support. It builds a Debian 13 rootfs with Node.js 22, PulseAudio, runtime libraries, and helper tools, then writes:

```text
app/src/main/assets/rootfs/rootfs-debian13-arm64.tgz
```

Generated runtime assets and downloaded native binaries are intentionally ignored by Git. Recreate them locally with the scripts above or let GitHub Actions build and cache them.

## Install From macOS

```bash
adb devices -l
./gradlew assembleDebug
adb install -r -d -t app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.tx5dr.bridge/.MainActivity
```

If no device appears in `adb devices -l`, enable Developer Options and USB debugging on the phone, then accept the debugging authorization dialog.

## GitHub Actions Nightly APK

Pushes to `main` build a signed nightly APK with `.github/workflows/android-apk.yml`, update the GitHub prerelease tag `nightly-android-bridge`, upload the APK to Aliyun OSS, and publish the APK manifest.

The workflow does not commit generated runtime assets. It downloads Termux PRoot/zstd, builds the Debian 13 arm64 rootfs with Docker/QEMU on cache miss, then stores those generated files in the GitHub Actions cache. Use the manual `workflow_dispatch` input `force_rebuild_assets=true` when Debian or Termux inputs need a forced refresh without changing scripts.

Required repository secrets:

- `TX5DR_ANDROID_KEYSTORE_BASE64`: base64-encoded Android release keystore.
- `TX5DR_ANDROID_KEYSTORE_PASSWORD`
- `TX5DR_ANDROID_KEY_ALIAS`
- `TX5DR_ANDROID_KEY_PASSWORD`
- `ALIYUN_ACCESS_KEY_ID`
- `ALIYUN_ACCESS_KEY_SECRET`
- `OSS_BUCKET`
- `OSS_ENDPOINT`
- `OSS_BASE_URL`
- `OSS_REGION`

CI sets `TX5DR_ANDROID_VERSION_CODE` from the GitHub run number and `TX5DR_ANDROID_VERSION_NAME` to `0.1.0-nightly.<UTC yyyyMMddHHmm>+<short_sha>`. Local debug builds keep the default `0.1.0-poc` version.

## Runtime Manifest

The default runtime manifest URL is:

```text
https://dl.tx5dr.com/tx-5dr/android-runtime/nightly/latest.json
```

The app expects a manifest containing one Android arm64 tarball asset:

```json
{
  "version": "1.0.0-nightly.202605180414+7d9c7e4",
  "assets": [
    {
      "name": "TX-5DR-1.0.0-nightly.202605180414.7d9c7e4-android-runtime-linux-arm64.tar.gz",
      "url": "https://dl.tx5dr.com/tx-5dr/android-runtime/nightly/TX-5DR-1.0.0-nightly.202605180414.7d9c7e4-android-runtime-linux-arm64.tar.gz",
      "sha256": "...",
      "size": 123,
      "platform": "android",
      "arch": "arm64",
      "package_type": "tar.gz"
    }
  ]
}
```

The app verifies HTTPS/download success, expected size when present, and `sha256`. It does not yet verify signed manifests.

## ADB Debug Control

Debug builds include an exported no-display `DebugCommandActivity` under `app/src/debug`. It is not part of release builds. Use `tools/adb-debug.sh` to control the app remotely without tapping the phone.

```bash
./tools/adb-debug.sh devices
./tools/adb-debug.sh install-apk
./tools/adb-debug.sh launch
./tools/adb-debug.sh install-runtime
./tools/adb-debug.sh start
./tools/adb-debug.sh stop
./tools/adb-debug.sh logs
./tools/adb-debug.sh dns-smoke
```

Common debug loops:

```bash
# Build and install the APK, then open the normal UI.
./tools/adb-debug.sh install-apk
./tools/adb-debug.sh launch

# Trigger runtime install/update with the saved manifest URL.
./tools/adb-debug.sh clear-logs
./tools/adb-debug.sh install-runtime
./tools/adb-debug.sh logs

# Start/stop PRoot and inspect logs once.
./tools/adb-debug.sh clear-logs
./tools/adb-debug.sh start
sleep 8
./tools/adb-debug.sh logs-once
./tools/adb-debug.sh stop

# DNS smoke inside the same PRoot bind setup used by the app.
./tools/adb-debug.sh dns-smoke

# Audio controls. Android RECORD_AUDIO permission must already be granted.
./tools/adb-debug.sh start-usb-audio
./tools/adb-debug.sh audio-smoke 3
./tools/adb-debug.sh output-smoke
./tools/adb-debug.sh stop-usb-audio

# USB serial controls. Use wireless ADB if the phone USB port is occupied by the radio.
./tools/adb-debug.sh start-usb-serial
./tools/adb-debug.sh stop-usb-serial
```

`logs` follows these tags:

```text
Tx5drBridge RuntimeManager AudioBridge UsbSerialBridge proot serial-pty NetworkAccess
```

## Audio Bridge

TX-5DR inside Linux reads an Android audio manifest and opens a Unix domain socket for the selected input/output device. Android exposes multiple USB audio devices plus the built-in microphone/speaker as explicit TX-5DR devices.

```text
Android AudioRecord PCM s16le/mono/48000 preferred
  -> latest-frame input queue
  -> /opt/tx5dr-data/runtime/sockets/audio-input-<id>.sock
  -> TX-5DR server Android audio backend
  -> decoder/spectrum pipeline

TX-5DR server Android audio backend
  -> /opt/tx5dr-data/runtime/sockets/audio-output-<id>.sock
  -> latest-frame output queue
  -> Android AudioTrack preferred output device
```

The manifest marks a USB-first default only for unconfigured or legacy profiles. Normal device selection happens inside the TX-5DR web audio settings; the Android shell only exposes device endpoints and shows whether a socket is currently in use. `AudioRecord` / `AudioTrack` are created lazily when TX-5DR connects to a socket, so idle devices do not hold Android audio resources. Latest-frame queues intentionally drop stale PCM after stalls so TX and voice output do not accumulate multi-second delay.

After starting the runtime and enabling audio, use the debug helper to inspect the manifest and sockets:

```bash
./tools/adb-debug.sh start
./tools/adb-debug.sh start-usb-audio
./tools/adb-debug.sh audio-smoke 3
```

Manual equivalent inside PRoot:

```bash
cat /opt/tx5dr-data/runtime/android-audio-devices.json
ss -lx | grep tx5dr-data
```

Then check whether TX-5DR enumerates the Android audio devices:

```bash
curl -s http://127.0.0.1:4000/api/audio/devices
```

## USB Serial / Hamlib Bridge

Android owns the physical USB serial device through `UsbManager`. Debian sees a PTY symlink generated by `tx5dr-android-serial-pty`:

```text
Hamlib in PRoot
  -> /opt/tx5dr-data/android-dev/ttyUSB0
  -> tx5dr-android-serial-pty
  -> /opt/tx5dr-data/runtime/sockets/serial-ttyUSB0.sock framed protocol
  -> AndroidUsbSerialBridge
  -> Android USB serial driver
  -> radio CAT/PTT serial interface
```

Useful checks after starting USB Serial and the runtime:

```bash
adb shell run-as com.tx5dr.bridge cat files/runtime/tx5dr-data/runtime/android-serial-devices.json
adb shell run-as com.tx5dr.bridge ls -l files/runtime/tx5dr-data/android-dev
```

Inside PRoot, Hamlib should use `/opt/tx5dr-data/android-dev/ttyUSB0` as the serial path. The helper forwards byte data and polls PTY termios changes to update baud/data bits/stop bits/parity on Android. DTR/RTS over PTY modem ioctls is experimental; if Android logs do not show control changes, use CAT PTT first and treat DTR/RTS/CW as a future dedicated host-control backend.

## Open Source License

This repository is licensed under **GNU GPL v3.0 or later**. See `LICENSE`.

Important third-party components keep their own licenses. See `THIRD_PARTY_NOTICES.md` and `app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt` for the notice bundle that accompanies APK builds. In particular:

- AndroidX, Jetpack Compose, Material 3, and Apache Commons Compress are Apache-2.0 licensed dependencies.
- The local USB serial driver copy is derived from usb-serial-for-android style code with original copyright headers retained.
- Generated APK assets may include separate executable components such as PRoot, zstd, libtalloc, and a Debian rootfs; these remain under their upstream licenses.
- The downloaded TX-5DR Android runtime is a separate distribution channel and remains under the TX-5DR runtime's license terms.

When distributing modified APKs, provide the corresponding source for this repository and preserve third-party notices and source-offer obligations for generated native/rootfs assets.

## Known Limits

- Only arm64 real Android 9+ devices are targeted.
- No Play Store compliance, AAB release, or stable channel has been done.
- Runtime self-update is implemented for the TX-5DR runtime; APK self-update installation is not implemented yet.
- USB serial bridge is experimental: CAT byte I/O and termios baud/parity sync are implemented; DTR/RTS/CW over PTY modem lines still needs hardware validation.
- Rootfs and runtime assets are large; generated assets are built in CI/cache instead of committed to Git.
- LAN browser access requires the client to be on a reachable network: either connected to the phone hotspot or on the same Wi-Fi. Wi-Fi AP isolation, cellular carrier addresses, VPNs, and aggressive vendor background limits can block inbound access.
- LAN URLs never include the admin token. The embedded WebView uses local `127.0.0.1` with token injection, while LAN browsers must use the normal auth flow.
