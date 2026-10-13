<p align="center">
  <img src="./app-desktop/src/main/resources/icons/mihon-desktop.png" alt="Mihon Desktop icon" width="112" height="112">
</p>

<h1 align="center">Mihon Desktop</h1>

<p align="center">
  <strong>Read on your desktop. Continue on Android. Discover more from the authors you follow.</strong>
</p>

<p align="center">
  <a href="#get-started"><img src="https://img.shields.io/badge/status-early%20development-F6BF1A?labelColor=941454" alt="Status: early development"></a>
  <a href="https://github.com/AltairCardinal/mihon-desktop/actions/workflows/desktop-ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/AltairCardinal/mihon-desktop/desktop-ci.yml?branch=main&amp;label=Desktop%20CI" alt="Desktop CI"></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-3072B3" alt="License: Apache 2.0"></a>
</p>

<p align="center">
  <a href="https://altaircardinal.github.io/mihon-desktop/">Project website</a> ·
  <a href="#get-started">Get started</a> ·
  <a href="#features">Features</a> ·
  <a href="#roadmap">Roadmap</a> ·
  <a href="#contribute">Contribute</a> ·
  <a href="https://github.com/AltairCardinal/mihon-desktop/issues">Issues</a>
</p>

<p align="center">
  <strong>English</strong> · <a href="./README.zh-CN.md">简体中文</a>
</p>

---

Mihon Desktop is an independently maintained, open-source project based on [Mihon](https://github.com/mihonapp/mihon). We are extending the reading experience with desktop support, smarter double-page layouts, cross-device reading progress, and tools for following authors and discovering their work.

Windows is the primary desktop target. This repository also includes macOS builds and its own Android app, with shared Kotlin code across platforms.

> **Early development:** This is a development project, not a stable release. Public release packages have not been published yet. You can build a preview from source, and early feedback and contributions are welcome.

<a id="features"></a>

## Features

| Feature | What you can try in the development version |
| --- | --- |
| **Desktop reading** | Manage your library and downloads in a desktop interface, with configurable reading modes and reading directions. |
| **Double-page reading and spread matching** | Read two pages together, display wide pages, and adjust page pairing manually. Desktop spread matching uses adjacent image edges to help identify pages that belong together. Android supports double-page reading and manual pairing; automatic matching is a further development goal. |
| **Multi-device sync** | Sync manga favorites, followed authors, read status, and reading position through a dedicated private GitHub repository, using compatible desktop and Android builds from this project. |
| **Author following and discovery** | Follow authors, browse their works and source versions, and periodically check configured sources for newly discovered works. Results depend on the sources and author information available. |
| **Language information** | Identify language information from source metadata, tags, and title or description text. The desktop author archive supports manual language corrections. Broader language filtering is part of the roadmap. |

The sync feature connects **this project’s builds**; the upstream Mihon Android app does not implement this sync protocol. Downloaded chapter images are outside the current sync scope.

The [feature guide](https://altaircardinal.github.io/mihon-desktop/features.html) is the maintained description of the fork’s features, workflows, and platform limits. The [change history](https://altaircardinal.github.io/mihon-desktop/changelog.html) records documentation updates. Future feature changes enter the public guide after explicit owner acceptance.

<a id="get-started"></a>

## Get started

### Release status

There are currently **no public release packages**. The [Releases page](https://github.com/AltairCardinal/mihon-desktop/releases) is the place to check for published builds. For now, use the source-build instructions below.

| Platform | Current scope |
| --- | --- |
| **Windows** | Primary desktop development and validation target; a local preview build is available through the project scripts. |
| **macOS** | Desktop build path available; source and build output must be on APFS for the preview workflow. |
| **Android 8.0+** | This repository maintains a separate Android app with its own build and application identity. |

Linux desktop packaging and validation are not currently configured.

### Build a desktop preview

Install **Git**, **JDK 21**, and **Python 3.11+**. Set `JAVA_HOME` to your JDK 21 installation and make Git and Python available on your command line. Configure **Android SDK Platform 36** and **Build Tools 36.0.0** for the Android modules in the shared build, using your own SDK path through `ANDROID_HOME` or `local.properties`. The repository includes the Gradle wrapper.

**Windows — PowerShell**

```powershell
git clone https://github.com/AltairCardinal/mihon-desktop.git
cd mihon-desktop
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1 -Preview
```

The build writes an isolated application directory under `app-desktop/artifacts/preview/windows/`. Open the executable at the **`Final unpacked EXE:`** path printed in the build log. Keep the complete application directory together.

<details>
<summary><strong>macOS — Terminal</strong></summary>

Clone the repository onto an APFS volume, then run:

```bash
git clone https://github.com/AltairCardinal/mihon-desktop.git
cd mihon-desktop
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./scripts/build-desktop.sh preview
```

The application is written under `app-desktop/artifacts/preview/macos/`.

</details>

Preview builds do not allocate a release version or run the full product test suite. They are intended for development and hands-on feedback.

For Android development, use `python scripts/build-android.py debug` after setting up the toolchain. See the [Android build guide](./docs/architecture/android-build-and-acceptance.md) for signing and other build modes.

### Try a reading workflow

1. Add reading content through your configured sources or local library.
2. Open a chapter and try double-page reading. Adjust the pairing if a cover, wide page, or split spread is placed incorrectly.
3. To continue across devices, open the sync panel in compatible builds from this project, connect GitHub, and create or join the same sync space.
4. Follow an author and explore the works found through your configured sources.

Use the [download-directory guide](./docs/desktop-download-directory.md) for desktop storage settings, moving downloads, and recovery.

<a id="roadmap"></a>

## Roadmap

The long-term goal is to expand what Mihon can do for reading, keeping a personal library, and discovering more work from its authors.

- **Windows experience:** Improve everyday reading, packaging, onboarding, and readiness for public releases.
- **Smarter double-page reading:** Improve spread detection and bring shared pairing rules and automatic matching to Android and desktop.
- **Reliable multi-device continuity:** Refine sync setup, conflict handling, progress restoration, and recovery.
- **Author discovery:** Improve author identity matching, work grouping, and discovery across configured sources.
- **Language recognition and filtering:** Improve language information and make filtering works by reading language more complete and accessible.

These are development directions. Scope and order will evolve with implementation work and user feedback.

<a id="contribute"></a>

## Contribute

We welcome developers, testers, designers, and people who can improve documentation or translations.

| Area | Useful contributions |
| --- | --- |
| **Reader and image processing** | Reproducible spread-pairing cases, matching improvements, and reader interaction fixes. |
| **Sync and shared Kotlin code** | Cross-device behavior, interrupted-sync recovery, and focused regression coverage. |
| **Authors and language information** | Author identity matching, source metadata handling, and language correction and filtering. |
| **Desktop experience** | Windows and macOS issues, keyboard navigation, accessibility, and packaging. |
| **Documentation and testing** | Clear setup instructions, translations, and reproducible reports from real reading sessions. |

Start with the [project Issues](https://github.com/AltairCardinal/mihon-desktop/issues). For a substantial change, describe the problem and proposed behavior before implementation. Keep pull requests focused, and follow the repository’s test-first workflow for changes that affect behavior.

See [CONTRIBUTING.md](./CONTRIBUTING.md) for the contribution workflow and [the website’s contribution guide](https://altaircardinal.github.io/mihon-desktop/contributing.html) for ways to help. Public feature documentation is updated after the project owner explicitly accepts the corresponding iteration.

### Find your way around the code

| Path | Responsibility |
| --- | --- |
| [`app-desktop/`](./app-desktop/) | Desktop UI, platform integration, and desktop runtime. |
| [`app/`](./app/) | This project’s Android app. |
| [`domain/`](./domain/) and [`data/`](./data/) | Shared business logic, data models, and persistence. |
| [`source-api/`](./source-api/) and [`source-local/`](./source-local/) | Source interfaces and local content support. |
| [`docs/`](./docs/) | Design proposals, implementation records, and technical guides. |

Read the [repository development rules](./AGENTS.md) for implementation and validation conventions. Documentation-only changes do not require product tests.

### Report a problem

Please report this project’s issues in [this repository](https://github.com/AltairCardinal/mihon-desktop/issues). Include:

- Your operating system and app version or commit.
- Reproduction steps, expected behavior, and what actually happened.
- Screenshots or relevant logs, with credentials and personal information removed.
- For pairing problems: page numbers, reading direction, and the expected page arrangement.
- For sync problems: the platforms involved and the last action taken on each device.

<a id="credits"></a>

## Credits and license

Mihon Desktop builds on the work of [Mihon](https://github.com/mihonapp/mihon), [Tachiyomi](https://github.com/tachiyomiorg/tachiyomi), and their contributors. This is an independently maintained downstream project with its own development and release process.

Licensed under the [Apache License, Version 2.0](./LICENSE). Existing copyright notices and attribution are preserved:

```text
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project
```

The application does not host manga content. Its developers are not affiliated with the content providers used through the application.
