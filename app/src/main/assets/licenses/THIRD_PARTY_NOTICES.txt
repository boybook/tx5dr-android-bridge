# Third-Party Notices

This notice file documents third-party code and generated binary assets used by the TX-5DR Android app. The TX-5DR Android app source in this repository is licensed under GNU GPL v3.0 or later unless a file or directory states otherwise.

## Android Gradle Plugin and AndroidX / Jetpack Compose

- Components: Android Gradle Plugin, AndroidX Activity Compose, Jetpack Compose Foundation/UI/Tooling, Material 3, Material Icons Extended.
- License: Apache License 2.0.
- Source: https://android.googlesource.com/ and https://github.com/androidx/androidx

## Apache Commons Compress

- Component: `org.apache.commons:commons-compress`.
- License: Apache License 2.0.
- Source: https://commons.apache.org/proper/commons-compress/

## usb-serial-for-android style drivers

- Location: `app/src/main/java/com/bg7yoz/ft8cn/serialport/`.
- Upstream project: https://github.com/mik3y/usb-serial-for-android
- License: MIT-style license from the upstream project.
- Notes: Original copyright headers are retained in copied source files.

Upstream license text:

```text
Copyright 2011-2013 Google Inc.
Copyright 2013 mike wakerly <opensource@hoho.com>

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## PRoot from Termux packages

- Generated APK component: `libproot_exec.so`, `libproot_loader.so`, `libproot_loader32.so`.
- Script: `tools/fetch-proot.sh`.
- Upstream project: https://github.com/proot-me/proot
- Termux package source: https://github.com/termux/termux-packages/tree/master/packages/proot
- License: GPL-2.0-or-later.
- Notes: The APK packages PRoot as a separate executable component invoked by the Android app. If you redistribute APKs built from this repository, keep the PRoot notices and provide access to the corresponding PRoot source used for the packaged binary.

## libtalloc from Termux packages

- Generated APK component: `libtalloc.so`.
- Script: `tools/fetch-proot.sh`.
- Upstream project: https://www.samba.org/ftp/talloc/
- Termux package source: https://github.com/termux/termux-packages/tree/master/packages/libtalloc
- License: LGPL-3.0-or-later.

## zstd from Termux packages

- Generated APK component: `libzstd_exec.so` and `libzstd.so`.
- Script: `tools/fetch-zstd.sh`.
- Upstream project: https://github.com/facebook/zstd
- Termux package source: https://github.com/termux/termux-packages/tree/master/packages/zstd
- License: BSD OR GPLv2 dual license as published by upstream zstd.

## Debian rootfs and system packages

- Generated APK component: `app/src/main/assets/rootfs/rootfs-debian13-arm64.tgz`.
- Script: `tools/build-rootfs.sh`.
- Base image: Debian 13/trixie arm64.
- Node.js source: NodeSource Node.js 22 repository.
- Installed package families include PulseAudio, ALSA/JACK runtime libraries, Hamlib, FFTW, GCC runtime libraries, and standard Debian utilities.
- License: each Debian package retains its own upstream license. Debian package source and copyright metadata are available through Debian source packages and `/usr/share/doc/*/copyright` inside the generated rootfs.

## TX-5DR Android runtime

The Android app downloads the TX-5DR runtime from the runtime manifest at install/update time. The runtime is not source code from this Android bridge repository and remains governed by the TX-5DR runtime distribution license.
