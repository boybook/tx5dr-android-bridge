# TX-5DR Android Bridge PoC

Native Kotlin Android bridge for running the TX-5DR Linux runtime on Android through a Debian 13 arm64 PRoot environment.

This project is intentionally a PoC and is not Play Store-ready. The first target is a real arm64 Android 9+ phone installed through local ADB. The app name is `TX-5DR`, and the package id is `com.tx5dr.bridge`.

## Goals

- Run a Debian 13 minbase rootfs inside Android app-private storage with PRoot.
- Install TX-5DR portable Android runtime releases from a manifest URL.
- Start the TX-5DR server on `127.0.0.1:4000` and the `client-tools` static/proxy service on `127.0.0.1:8076`.
- Display the web UI in an Android WebView at `http://127.0.0.1:8076`.
- Provide debug-only ADB commands for install/start/stop/log collection without touching the phone UI.
- Experiment with Android microphone capture bridged into Linux PulseAudio inside PRoot.

## Architecture

```text
Android app (Kotlin)
  MainActivity
    - status panel
    - manifest URL input
    - install/start/stop buttons
    - WebView
    - log panel
  BridgeService
    - foreground service wrapper for long-running actions
  BridgeRuntime
    - rootfs install
    - TX-5DR release install/update
    - PRoot process lifecycle
    - health checks for :4000 and :8076
  MicBridge
    - Android AudioRecord PCM capture
    - local TCP PCM stream on 127.0.0.1:4719

App private files directory
  runtime/rootfs/                 Debian 13 minbase rootfs
  runtime/tx5dr/releases/<ver>/   TX-5DR portable runtime releases
  runtime/tx5dr/current           symlink to active release
  runtime/tx5dr-data/             persistent TX-5DR data/config/log/cache
  runtime/host-libs/              Android-side versioned native library aliases
  runtime/proot-tmp/              PRoot temp directory

PRoot Debian runtime
  /opt/tx5dr/current              active TX-5DR release
  /opt/tx5dr-data                 persistent data bind mount
  127.0.0.1:4000                  TX-5DR server
  127.0.0.1:8076                  client-tools web proxy/static server
  127.0.0.1:4718                  PulseAudio native TCP protocol
```

## Runtime Logic

1. `Install/Update` first ensures the embedded Debian rootfs is extracted to `files/runtime/rootfs`.
2. The app downloads the configured runtime manifest, selects the `android` + `arm64` tarball, downloads it, checks `sha256`, then extracts it to `files/runtime/tx5dr/releases/<version>`.
3. `files/runtime/tx5dr/current` is switched to the newly installed release. The symlink is relative (`releases/<version>`) so it remains visible after PRoot bind mounts `files/runtime/tx5dr` as `/opt/tx5dr`.
4. `Start` prepares Android-side native host libraries, then launches PRoot with bind mounts for `/opt/tx5dr`, `/opt/tx5dr-data`, `/proc`, `/dev`, and `/sys`.
5. Inside Debian, the startup script runs PulseAudio, starts the TX-5DR server, starts `client-tools`, and keeps the PRoot process alive until either child exits or Android sends stop.
6. The Android side periodically health-checks `GET http://127.0.0.1:4000/api/hello` and `GET http://127.0.0.1:8076/`.

The Android runtime does not use `systemd` or `nginx`. Static web serving and API proxying are handled by `packages/client-tools/src/proxy.js`, matching the Electron-oriented client-tools path.

## Native Packaging Notes

Android 10+ should not execute binaries extracted into app-private writable storage. Runnable host tools are therefore packaged as native libraries under `app/src/main/jniLibs/arm64-v8a` after running `tools/fetch-proot.sh` and `tools/fetch-zstd.sh`.

`tools/fetch-proot.sh` installs:

- `libproot_exec.so`: Termux `proot` executable renamed for APK native packaging.
- `libproot_loader.so`: 64-bit PRoot loader used through `PROOT_LOADER`.
- `libproot_loader32.so`: 32-bit PRoot loader used through `PROOT_LOADER_32`.
- `libtalloc.so`: Termux `libtalloc`; runtime recreates `libtalloc.so.2` in `files/runtime/host-libs`.

At runtime the app sets `LD_LIBRARY_PATH`, `PROOT_TMP_DIR`, `PROOT_LOADER`, and `PROOT_LOADER_32` before launching PRoot. This mirrors the packaging pattern used by existing Android PRoot apps such as tiny_computer.

## Prepare Assets

A debug APK can be compiled without large runtime assets, but install/start will fail until the PRoot and rootfs assets exist.

```bash
cd /Users/fangyizhou/Documents/coding/tx5dr-android-bridge
./tools/fetch-proot.sh
./tools/fetch-zstd.sh
./tools/build-rootfs.sh
```

`tools/build-rootfs.sh` requires Docker with `linux/arm64` support. It builds a Debian 13 rootfs with Node.js 22, PulseAudio, runtime libraries, and helper tools, then writes `app/src/main/assets/rootfs/rootfs-debian13-arm64.tgz`.

Large generated assets and downloaded native binaries are intentionally ignored by Git. Recreate them locally with the scripts above.

## Build And Install From macOS

```bash
adb devices -l
./gradlew assembleDebug
./gradlew installDebug
adb logcat -s Tx5drBridge RuntimeManager AudioBridge proot mic-linux
```

If no device appears in `adb devices -l`, enable Developer Options and USB debugging on the phone, then accept the debugging authorization dialog.

## Runtime Manifest

The default manifest URL is:

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

The app currently verifies HTTPS/download success, expected size when present, and `sha256`. It does not yet verify signed manifests.

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

# Trigger runtime install/update with a custom manifest URL.
./tools/adb-debug.sh install-runtime https://dl.tx5dr.com/tx-5dr/android-runtime/nightly/latest.json

# Start/stop PRoot and inspect logs once.
./tools/adb-debug.sh clear-logs
./tools/adb-debug.sh start
sleep 8
./tools/adb-debug.sh logs-once
./tools/adb-debug.sh stop

# Mic experiment controls. Android RECORD_AUDIO permission must already be granted.
./tools/adb-debug.sh start-mic
./tools/adb-debug.sh stop-mic
```

`logs` follows these tags:

```text
Tx5drBridge RuntimeManager AudioBridge proot mic-linux
```

## Mic/Pulse Experiment

The first audio experiment is Android mic capture into Linux PulseAudio:

```text
Android AudioRecord PCM s16le/mono/44100
  -> 127.0.0.1:4719 TCP stream
  -> tx5dr-android-mic-injector inside Debian
  -> PulseAudio AndroidSink null sink
  -> AndroidMic remap source
```

After starting the runtime and enabling the mic bridge, smoke-test inside PRoot:

```bash
pactl list short sources | grep AndroidMic
timeout 3 parec -d AndroidMic --raw --rate=44100 --format=s16le --channels=1 > /tmp/android-mic.raw
ls -lh /tmp/android-mic.raw
```

Then check whether TX-5DR can enumerate the Pulse/RtAudio source:

```bash
curl -s http://127.0.0.1:4000/api/audio/devices
```

If Pulse/RtAudio is unreliable on Android PRoot, the next phase should switch to a dedicated Kotlin PCM bridge and a TX-5DR `android-host` audio backend.

## Known Limits

- Only arm64 real Android 9+ devices are targeted.
- No Play Store compliance work has been done.
- No background automatic updater or rollback UI yet.
- USB serial/CW bridge is not implemented yet.
- Rootfs and runtime assets are large; future work should consider asset delivery or differential updates.
