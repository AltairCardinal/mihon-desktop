---
title: Get started
description: Build a local preview and try the reading workflow.
---

# Get started

Mihon Desktop is in early development. **There is no public release package yet.** The current way to try it is to build from source. Published packages, when available, will appear on this fork’s [Releases page](https://github.com/AltairCardinal/mihon-desktop/releases).

## Choose a platform

| Platform | Current scope |
| --- | --- |
| Windows | Primary desktop target. Build a local preview with the project script. |
| macOS | Desktop build entrypoint is available. Keep source and output directories on an APFS volume. |
| Android 8.0+ | This repository maintains its own app identity and build workflow. |
| Linux | Desktop packaging and acceptance are not configured. |

## Prepare the toolchain

Install **Git, JDK 21, and Python 3.11+**. Point `JAVA_HOME` to your JDK 21 installation and make Git and Python available in your terminal.

The shared build includes Android modules. Configure **Android SDK Platform 36** and **Build Tools 36.0.0**, and set `ANDROID_HOME` or your local `local.properties` to your own SDK location. The repository includes the Gradle wrapper.

## Build a Windows preview

Run in PowerShell:

~~~powershell
git clone https://github.com/AltairCardinal/mihon-desktop.git
cd mihon-desktop
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1 -Preview
~~~

The script writes an independent application directory under `app-desktop/artifacts/preview/windows/`. Run the executable printed after **`Final unpacked EXE:`** in the build log. Keep the full application directory together.

## Build a macOS preview

Clone the repository on an APFS volume, then run:

~~~bash
git clone https://github.com/AltairCardinal/mihon-desktop.git
cd mihon-desktop
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./scripts/build-desktop.sh preview
~~~

The application output is under `app-desktop/artifacts/preview/macos/`.

::: tip What a preview means
A preview does not allocate an official release version and does not run the full product test suite. It is intended for development and everyday feedback. Building a preview is not equivalent to completing native interaction checks or the release acceptance matrix.
:::

## Build the Android development app

After configuring the toolchain, run:

~~~bash
python scripts/build-android.py debug
~~~

For signing, candidates, installation boundaries, and other build modes, use the repository’s [Android build and acceptance guide](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/architecture/android-build-and-acceptance.md). Development and release identities are separate.

## Try a reading journey

1. Add reading content from a configured source or your local library.
2. Open a chapter and try two-page reading. Adjust pairing when a cover, landscape page, or spread does not line up.
3. To continue on another device, connect GitHub in the sync panel and join the same sync space with compatible builds of this fork.
4. Follow an author and explore works found in your configured sources.
5. Inspect language information in the desktop author archive and correct inaccurate metadata.

Check the [feature boundaries](./features.md) before testing across platforms. If something goes wrong, [report a reproducible issue](./contributing.md#report-a-problem).

The app does not host manga content. Its developers are not affiliated with the content providers used by the app.
