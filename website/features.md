---
title: Current features
description: What this independent fork adds to Mihon, and where the current boundaries are.
---

# Current features

This is the single current feature guide for this fork. It focuses on what Mihon Desktop adds to the upstream Mihon experience; it is not a complete manual for every inherited feature.

::: info Public documentation baseline · October 13, 2026
The initial guide carries forward the repository’s already-public README and guides. It describes the early development source baseline, **not a public release or a new acceptance decision**. Future feature changes enter this guide only after the project owner explicitly accepts them. [See the documentation history](./changelog.md).
:::

| Addition | Desktop | This fork’s Android app |
| --- | --- | --- |
| Desktop reading | Windows is the primary target; macOS has a build entrypoint | Android remains a companion platform |
| Two-page reading | Manual pairing and image-edge-assisted spread matching | Two-page reading and manual pairing |
| Multi-device sync | Compatible builds share a GitHub-backed sync space | Compatible builds join the same space |
| Author following and discovery | Follow authors and explore works from configured sources | Shared author behavior; interface and results depend on platform and sources |
| Work language information | Metadata-based identification and manual correction in the author archive | Broader language filtering remains a development direction |

## Desktop reading {#desktop-reading}

Mihon’s reading and library experience gains a desktop home. Use the desktop interface to manage your library and downloads, open chapters, and choose a reading mode and direction.

**Try it:** [build a desktop preview](./getting-started.md), add a work through a configured source or local library, then open a chapter.

**Windows startup feedback:** launch the packaged EXE to see a dark Mihon card with a starting message and an accent strip while the application initializes. The card is positioned at the center of the work area on the monitor containing the mouse pointer before it first becomes visible; launching from a secondary monitor no longer flashes it on the primary monitor first. It closes automatically when the first application window opens. The card appears after JVM/AWT initialization and is a static waiting indicator, not a progress percentage. Startup timing depends on the machine; restoring a maximized window can still leave a brief gap before its content is presented. This feature is accepted for Windows packaged builds only; the main window retains its existing saved-position behavior.

**Windows paging controls:** in Reader settings → Reading mode, select **Paged** (翻页式 in Chinese). Under **Pager**, choose **Left to right** or **Right to left**; only one direction can be selected. Single-page and two-page layouts use this direction. Automatic mode keeps its adaptive right-to-left behavior and disables manual direction selection; Webtoon does not show paging direction controls.

**Manga details and chapter list:** The chapter list provides native dynamic scrollbars in both dual-pane and single-pane layouts, highlighting on scroll or hover. When the floating "Continue" action button is visible in the bottom-right corner, the list automatically maintains an 88dp bottom clearance so the final chapter row remains completely clear of the button when scrolled to the end.

**Chapter action feedback:** On Windows, successful or skipped chapter actions on the manga detail page no longer show the success/skip/failure count snackbar. Failure feedback and the optional Add to library action remain available.

**Desktop fullscreen reader mode:** Press **F11** or click the fullscreen button to the left of the Settings button in the top reader bar to enter or exit fullscreen mode. Upon entering fullscreen, a corner tutorial bubble reminds the user of the F11 shortcut and automatically dismisses after 3 seconds (and is permanently suppressed once acknowledged). In fullscreen, the top and bottom navigation bars are hidden by default and reveal when the cursor hovers near the top edge (≤ 48dp) or bottom edge (within 80dp), hiding again when the pointer leaves. Pressing **Esc** exits fullscreen mode.

**Windows comic continuity:** The displayed comic remains visible through fullscreen switches, including automatic single-page/two-page layout changes. This acceptance covers the brief image disappearance; occasional vertical movement when entering fullscreen remains under investigation.

**Source image downloads and access errors:** Windows queued chapter downloads preserve the source’s image request, including its headers, client and custom request method/body. Retry a failed chapter from the download queue after switching to the updated build. HTTP 403 means access was denied and is no longer presented as a login requirement; HTTP 401 retains the login prompt. Source network configuration failures remain visible instead of silently sending a fallback request. The shared Android error mapping also distinguishes these statuses. The accepted Windows candidate and the limits of the Android and browser checks are recorded in the [change history](./changelog.md).

**Platform boundaries:** Windows is the primary desktop development and validation target. The repository also maintains a macOS build entrypoint; macOS preview builds require the source and output directories to be on an APFS volume. Linux desktop packaging and acceptance are not configured. A successful preview build does not establish release readiness.

## Two-page reading and spread matching {#two-page-reading}

Read adjacent pages side by side, display landscape pages, and adjust pairing manually when a cover or page sequence shifts the layout.

**Try it:** open a chapter, select two-page reading, and inspect the page order. If a pair is wrong, adjust it manually in the reader. Use a chapter you know well to compare the result with the intended spread.

**Android reading direction:** Reader settings → Reading mode offers **Double page (right to left)** and **Double page (left to right)**. In Simplified Chinese these appear as **双页式（从右到左）** and **双页式（从左到右）**. Page placement and navigation follow the selected direction. Older clients that do not recognize the new left-to-right two-page setting fall back to single-page left-to-right when reading its saved work setting.

**Desktop assistance:** image-edge information from adjacent pages helps identify spreads. This is an aid to pairing, not a guarantee that every scan or layout can be recognized. Manual adjustment remains part of the reading workflow.

**Android boundary:** this fork’s Android reader supports two-page reading and manual pairing. Automatic spread matching on Android remains a development direction.

## Multi-device favorites and reading progress {#multi-device-sync}

A dedicated **private GitHub repository** backs a sync space shared by compatible desktop and Android builds of this fork.

The documented sync scope is:

- Manga favorites.
- Followed authors.
- Chapter read status.
- Reading position.

**Try it:** connect GitHub from the sync panel on one device, create or join a sync space, then connect a compatible build on the other device to the same space. Use the panel’s status and feedback to follow the operation.

**Compatibility boundary:** upstream Mihon for Android does not implement this fork’s sync protocol. Use compatible builds from this repository on both devices.

**Content boundary:** downloaded chapter images are not synchronized. Each device still needs access to its reading content. Sync is for the documented library and reading state, not a transfer of every application file.

## Author following and new-work discovery {#authors-and-discovery}

Follow authors and explore their works, including versions found in different sources. Discovery checks configured sources periodically for newly found works associated with followed authors.

**Try it:** open the author archive, follow an author, and browse the works and source versions found for that author. Keep the relevant sources configured so that discovery can search them.

**Discovery boundary:** results depend on each source’s search capability and author metadata. This is not a universal catalog or a promise to find every work. Different names, incomplete credits, and inconsistent source data can affect matching.

## Work language information {#work-language}

The project identifies language information from source metadata, tags, and text in titles or descriptions. The desktop author archive also lets you correct language information manually.

**Try it:** inspect a work’s language information in the desktop author archive and correct it when the available metadata is misleading.

**Recognition boundary:** this is metadata-based identification. It does **not** inspect text inside page images with OCR, and a source’s language does not always establish the language of a particular edition.

**Filtering boundary:** more complete language filtering is a development direction. This baseline does not claim uniform, finished filtering across every library, search, author, and platform surface.

## Other adaptations and additions {#other-additions}

These smaller additions are documented in the repository’s existing guides.

| Capability | Entry point and boundary | Details |
| --- | --- | --- |
| Desktop download location | Settings → Downloads → Download directory. Changes take effect after restarting; existing downloads are not moved automatically. | [Storage and manual migration](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/desktop-download-directory.md) |
| Desktop network routing | Settings → Network, with per-extension overrides for requests using Mihon-managed clients. Routes to direct access, system policy, or a configured proxy; it does not manage proxy subscriptions or nodes. | [Network routing design and limits](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/architecture/desktop-network-routing.md) |
| External-browser source verification | Open the browser from source verification, complete the site challenge, and explicitly import the current domain’s clearance cookie. The optional helper reads only that domain’s clearance value on request. | [Cookie Helper usage and permissions](https://github.com/AltairCardinal/mihon-desktop/blob/main/browser-extension/mihon-cookie-helper/README.md) |
| Missing-extension suggestions | The extensions screen can suggest missing extensions associated with library favorites, with individual or batch installation flows. Availability depends on configured repositories and platform installation rules. | [Delivery record and platform boundaries](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/2026-09-17-extension-install-suggestions-handoff.md) |
| Upcoming chapter estimates | Open Upcoming from the desktop Updates screen. Expected dates are predictions, not guaranteed publication schedules. | [Documented desktop differences](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md#desktop-产品差异) |
| Desktop Webtoon auto-scroll | Enable auto-scroll and choose its speed in reader settings for Webtoon reading. This guide does not claim matching Android support. | [Documented desktop differences](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md#desktop-产品差异) |
| Desktop extension compatibility | The APK-to-JAR compatibility layer adapts supported Android extension behavior for desktop. It does not guarantee that every Android extension can run. | [Documented desktop differences](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md#desktop-产品差异) |
| Desktop input and system integration | Keyboard and mouse interaction, wide layouts, and desktop file/directory and clipboard integration adapt the app to desktop workflows. Platform support varies by operation. | [Platform comparison](https://github.com/AltairCardinal/mihon-desktop/blob/main/docs/MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md) |

## Where the project is heading {#direction}

The project’s five directions remain Windows reading, smarter two-page matching, reliable multi-device continuity, author-centered discovery, and better language identification and filtering.

Work in progress may exist in code, design documents, or prototypes before it appears here. Those artifacts are not evidence of owner acceptance or a public release. See [how accepted changes update these docs](./contributing.md#accepted-documentation) and the [changelog](./changelog.md).
