---
title: Changelog
description: A traceable history of public feature documentation, separate from the current feature guide.
---

# Changelog

Read [Current features](./features.md) for what the project currently documents. This page records changes to that baseline; it does not accumulate full copies of the guide.

Public product releases and documentation updates are different events. A date here is not a release version. Implementation work enters the public feature description only after explicit project-owner acceptance.

## October 29, 2026 — Stable Windows fullscreen transitions

**Accepted fix documentation · no public product release**

- Entering and exiting fullscreen in the tested Windows reader case keeps the comic visible without the reported vertical jumps, black/white flashes, or top-left white block.
- Accepted Windows preview: `0.11.24.6.693c6a2` (preview ID `main-0de74b9dc18544099ea272686b32d26a`); accepted source: `693c6a2618`; [integrated implementation](https://github.com/AltairCardinal/mihon-desktop/commit/ee54c0cfa8c83b8f6cb5140b3ade00f288a3e3c0). The application code is unchanged by the integration.
- Forty focused checks passed, including native transition pixels, window geometry, interrupted switches and resource cleanup. The packaged extension runtime check passed. This acceptance covers the tested Windows reading path; other platforms and fallback paths without a retained frame were not accepted by these results.

Acceptance basis: the project owner explicitly accepted this candidate in private chat on October 29, 2026 and requested main integration and a push.

## October 29, 2026 — Windows fullscreen comic continuity

**Accepted fix documentation · no public product release**

- Comics no longer briefly disappear during fullscreen switches, including automatic single-page/two-page layout changes.
- Accepted Windows preview: `0.11.24.6.36ea4d8` (preview ID `main-03d8b0fa240d4eb38f6423bf52c52eba`); accepted source: `36ea4d8ee0`; [integrated implementation](https://github.com/AltairCardinal/mihon-desktop/commit/b416ff8250119bd7d331bda1589fbb6f2c408423).
- This acceptance covers removal of the image disappearance on Windows. Occasional vertical movement on entry remains a known issue in this candidate; other platforms and complete fullscreen motion were not accepted.

Acceptance basis: the project owner confirmed in private chat on October 29, 2026 that the flicker was gone and separately reported the remaining vertical movement.

## October 29, 2026 — Quiet successful chapter actions on Windows

**Accepted candidate documentation · no public product release**

- Successful or skipped manga-detail chapter actions no longer display the success/skip/failure count snackbar. Failure feedback and the optional Add to library action remain available.
- Accepted Windows preview: `0.11.24.9.9ab6aa9` (preview ID `20261028T175303Z-ad157712`); implementation: `06da0f0188`.
- Seven focused interaction tests and the packaged extension runtime check passed. This acceptance covers the Windows chapter feedback change, not other platforms or a public product release.

Acceptance basis: the project owner explicitly accepted this candidate in private chat on October 29, 2026 and requested main integration and a push.

## October 29, 2026 — Source image requests and accurate access errors

**Accepted fixes and audit documentation · no public product release**

- Windows queued downloads now use the source’s complete image request. This fixes ManHuaGui image access failures caused by omitted source headers and also preserves custom request methods and bodies.
- Source network getter failures no longer silently fall back; bundled extension HTTP exceptions retain their error classification, and network interruptions are distinguished from disk failures.
- Shared Android/Desktop source errors distinguish HTTP 401 login requirements from HTTP 403 access denial. Existing persisted download errors with an explicit HTTP 403 message are read back as access denial.
- Accepted Windows preview: `0.11.24.9.d11630b` (preview ID `20261028T163014Z-3c5714dd`); [implementation and audit](https://github.com/AltairCardinal/mihon-desktop/commit/d11630bbd8b950177ca754d0877445e773b0ff78).
- Evidence: 168 focused tests, including shared JVM/Android contracts and a real ManHuaGui extension through production desktop wiring. The Windows packaged runtime check passed. Android device operation, system-browser fallback and macOS native interaction were not accepted as runtime-tested by these results.

Acceptance basis: the project owner explicitly accepted these fixes and the cross-platform audit in private chat on October 29, 2026 and requested main integration and a push. This is not a public product release or a claim that every third-party source has been verified.

## October 29, 2026 — Windows startup card on the launch monitor

**Accepted candidate documentation · no public product release**

- The startup card is positioned on the monitor containing the mouse pointer before its first visible frame, removing the primary-monitor flash when launching from a secondary monitor.
- Replaced the JVM-managed splash with a lightweight AWT window. Feedback now waits for JVM/AWT initialization; the main window retains its existing saved-position behavior.
- Accepted candidate: Windows preview `0.11.24.9.f9b8b2a` (preview ID `20261028T173231Z-78bf18d1`); implementation: `39d6243ac5`.
- Scope: Windows packaged startup feedback, including normal and maximized startup on a secondary monitor. Other platforms and a formal product release are not covered.

Acceptance basis: the project owner explicitly accepted this Windows candidate in private chat on October 29, 2026 and requested integration into main and a push. No public product release was allocated.

## October 17, 2026 — Desktop fullscreen reader mode

**Accepted candidate documentation · no public product release**

- Desktop reader now provides a dedicated fullscreen mode toggled via the F11 shortcut or the fullscreen icon next to Settings in the top reader bar.
- Upon entering fullscreen, a transient corner tutorial bubble guides the user with the F11 shortcut, automatically dismisses after 3 seconds, and integrates with the tutorial system to persist acknowledgment.
- In fullscreen mode, the top and bottom navigation bars are hidden by default and auto-reveal when hovering near the top edge (≤ 48dp) or bottom edge (within 80dp), smoothly hiding again when moving away. Pressing Esc exits fullscreen mode.
- Hardened against window disposal race conditions during rapid fullscreen toggling and cleared pairing certification targets across chapter switches in fullscreen and dual-page modes.
- Accepted candidate: Windows preview `0.11.20.6.53e23eb` (preview ID `20261017T151740Z-ec5c850a`).
- Scope: Windows desktop fullscreen reading mode and edge hover navigation; does not claim other platforms or a formal release.

Acceptance basis: the project owner explicitly accepted this Windows preview build in private chat on October 17, 2026 and requested a push. No public product release was allocated.

## October 17, 2026 — Manga detail chapter scrollbar and continue FAB clearance

**Accepted candidate documentation · no public product release**

- The chapter list in manga details now provides a native dynamic scrollbar in both dual-pane and single-pane layouts. It stays subtly visible at rest, highlights during scrolling and hovering, smoothly fades out after scrolling stops, and adds 12dp end padding to prevent text overlap.
- Resolved floating "Continue" button obstruction: when the bottom-right FAB is visible, the chapter list automatically applies an 88dp bottom content padding so the final chapter row is never covered when scrolled to the end. It returns to compact padding when the FAB is hidden.
- Accepted candidate: Windows preview `0.11.20.6.53e23eb` (preview ID `20261017T140626Z-b7e01dc2`).
- Scope: Windows desktop manga details chapter list interaction and FAB clearance; does not claim other platforms or a formal release.

Acceptance basis: the project owner explicitly accepted this Windows preview build in private chat on October 17, 2026 and requested a push. No public product release was allocated.

## October 14, 2026 — Windows startup feedback

**Accepted candidate documentation · no public product release**

- Windows packaged EXEs now show a native Mihon startup card before application initialization, with a static starting message and accent strip. The splash closes automatically when the first application window opens.
- Accepted preview: `0.11.19.91.67c6609`; implementation: `67c6609b5b`.
- Scope: Windows startup feedback, including normal and maximized startup. Timing depends on the machine; this is not a progress percentage or a guarantee of an uninterrupted transition to the main window. Other platforms are not covered.

Acceptance basis: the project owner explicitly accepted this Windows startup prompt in private chat on October 14, 2026 and requested a push. No public product release was allocated.

## October 14, 2026 — Reader mode and direction controls

**Accepted candidate documentation · no public product release**

- Windows combines the two paging mode entries into **Paged**, with mutually exclusive direction choices under **Pager**. Automatic mode remains adaptive right-to-left; Webtoon has no paging direction controls.
- Android localizes the right-to-left double-page option in Chinese and adds a working left-to-right double-page option. Older clients fall back to single-page left-to-right for the new saved setting.
- Accepted candidates: Windows preview `0.11.19.91.e659859`; Android `0.19.4-aex.73`, versionCode `91`, installed on the owner's Huawei PCE-W30 tablet.
- Implementation: `b551b7dd70`; candidate source/version allocation: `e659859e42`.

Acceptance basis: the project owner explicitly accepted these Windows and Android reader changes in private chat on October 14, 2026. This acceptance covers the listed controls and reading directions, not other platforms or a full release matrix.

## October 13, 2026 — Documentation baseline

**Documentation update · no product release**

- Established the English-default and Simplified Chinese documentation site.
- Collected the five project directions and their already-public scope: desktop reading, two-page pairing, multi-device sync, author discovery, and language information.
- Linked existing guides for desktop download locations, network routing, browser-assisted source verification, and missing-extension suggestions.
- Separated the single current feature guide from chronological change notes and per-page Git history.
- Documented how contributors and agents update public claims after owner acceptance.

This entry migrates existing public descriptions. It does not certify a new build, declare a release, or accept additional work in progress.

Baseline sources: the [public README at commit 87f8f89](https://github.com/AltairCardinal/mihon-desktop/blob/87f8f895dbb6fc4055c86e3a69043cc4dd30841d/README.md) and the existing guides linked in [Other adaptations and additions](./features.md#other-additions).

## How history is preserved

The **View this page’s history** link on each page opens its Git commits. Open an earlier commit to inspect the wording and diff for that revision. The [feature guide’s history](https://github.com/AltairCardinal/mihon-desktop/commits/main/website/features.md) tracks replacements as well as additions.

Future accepted changes receive concise dated entries with implementation and acceptance references. When a real public release exists, its entry can also link to the corresponding release. There is no separate version catalog or duplicated current guide to maintain.
