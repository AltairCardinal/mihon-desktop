---
title: Changelog
description: A traceable history of public feature documentation, separate from the current feature guide.
---

# Changelog

## 2026-11-13 — Android two-page swipe repair accepted

The project owner accepted Android `0.19.4-aex.137 / vc155` in a private review on 2026-11-13. The candidate contains implementation `fffacab00c` and source `f22079950c`: two-page preparation-window reconciliation now retains one native holder per pager position, preserving drawn content and forward/reverse navigation while images load. Nine focused regressions passed, including RTL/LTR and interrupted settling. This is acceptance of the local Android candidate, not a new public release or acceptance of other platforms.

## 2026-11-12 — Reader settings prerelease downloads

[Windows x64 preview and signed Android aex.136 / vc154 downloads](https://github.com/AltairCardinal/mihon-desktop/releases/tag/reader-settings-preview-20261112) are now public. These are the accepted `787f06b` artifacts, not rebuilt packages or a stable release. The Windows ZIP retains its preview identity and includes the complete app/runtime. Asset sizes and GitHub SHA-256 digests match the local files. The full Desktop test run remains incomplete; cold-tail pairing and timing-sensitive tests remain unresolved as described in the release notes. The later `14c7ba5aa1` timing repair is not included in these binaries. The in-app update feed is unchanged.

## 2026-11-12 — Android and Windows reader settings accepted

The project owner accepted the Android `0.19.4-aex.136` and Windows `0.11.24.10.787f06b` candidates in a private review on 2026-11-12. The accepted scope includes the shared reading-mode controls, a global default summary row opening a dialog, image and general settings, and grouped E-Ink options. Horizontal Auto enables the single-page image checkboxes when the actual layout is single-page; a two-page layout hides and disables them while preserving their values. Source: `787f06b` (the Android candidate uses the corresponding Android inputs). This acceptance covers these settings on Android and Windows, not macOS or a complete stable-release matrix.

## 2026-11-11 — Windows reader settings design accepted

**Accepted local preview · not a public product release**

The project owner accepted Windows preview `0.11.24.10.c95438e` in a private review on 2026-11-11. The grouped default/custom controls, horizontal and vertical reading, default summary, mode-dependent options, and the then-current single-page Split wide pages control were accepted. Source: `c95438e394e0e6b2a3ac1d7d1e1c9260cd6a2534` (including the preceding visual refinements). Android and the later settings changes were accepted on November 12 as recorded above.

Read [Current features](./features.md) for what the project currently documents. This page records changes to that baseline; it does not accumulate full copies of the guide.

Public product releases and documentation updates are different events. A date here is not a release version. Implementation work enters the public feature description only after explicit project-owner acceptance.

## November 8, 2026 — Windows library category dragging

The project owner accepted Windows preview `0.11.24.6.79412b6` on November 8, 2026 in a private conversation and authorized main integration and push. Implementations: `ebf5ffe7be` and `79412b6106`. Custom library categories can be dragged immediately, without a long press; releasing saves the order, while clicking still switches categories. The default category remains fixed, with edge scrolling and failed-save recovery. Acceptance covers Windows only; no public product release is implied.

## November 8, 2026 — Android rapid-turn review follow-up accepted

**Accepted candidate documentation · no public product release**

The project owner accepted tablet candidate `0.19.4-aex.129` (code 147) on November 8, 2026 in a private conversation. Source: `88103320e54da29d8cc43bc8a81f67d8cd04169f`, already integrated into main. This candidate includes the rapid-turn and loading-overlay fixes and the review follow-up that preserves reverse navigation when late geometry redescribes the selected empty slot. Acceptance remains limited to the reported downloaded Android two-page case; no new universal timing claim or public release is implied.

## November 7, 2026 — Android rapid two-page turns

**Accepted candidate documentation · no public product release**

- Downloaded two-page reading starts preparation during consecutive swipes and preserves prepared native images through eligible empty-slot regrouping. Replacing a slot also removes its retired loading and error overlays, preventing a spinner from remaining over an already drawn comic. The six-image preparation window and bounded concurrency are retained.
- Accepted tablet candidate: `0.19.4-aex.128` (code 146), based on implementation `f77d44627e` with the loading-overlay follow-up. On November 7, 2026 the project owner confirmed that the spinners disappeared and no visible delay was currently observed, then authorized implementation review and main integration/push. Acceptance covers this downloaded Android two-page case, without a universal latency claim or public product release.

## November 7, 2026 — Android and Windows chapter entry

**Accepted candidate documentation · no public product release**

- Android keeps the selected last page when activating the previous chapter and no longer carries the outgoing chapter's page index into the next chapter. Windows separates toolbar chapter buttons, which open the first page, from backward page turns, which enter the previous chapter's last page. Read-chapter reopening and unread continuation are retained.
- Accepted candidates: Android `0.19.4-aex.125` (code 143, installed on the reporting tablet) and Windows `0.11.24.6.ae5bb3b`; both were built from `ae5bb3bb5aca9e84e6616a1183129e6f0a25e77b`. Main implementations: Android `dc5f4c4c77` and Windows `011681faac`, following the earlier page-isolation fix `4bdca36f34`. The integrated source passed 49 focused tests.
- Acceptance basis: the project owner confirmed the chapter-entry fixes on both delivered candidates on November 7, 2026 in a private conversation and authorized main commit/push. The Windows optional CDS archive was unavailable; runtime checks passed. This acceptance does not establish macOS runtime behavior or a new startup-performance result and does not publish a product release.

## November 6, 2026 — Windows reader preparation

The accepted Windows candidate reduces repeated preparation when opening chapters while preserving image quality, page order, and normal page turning. Evidence supports the local work reduction, but does not establish a stable first-open speedup or a fixed millisecond gain; results vary with device and content. This acceptance covers the Windows preview only.

Implementation: `104851e5c54c696557c68820c2e2bf840d152bcd`. Acceptance basis: the project owner accepted this Windows scope in a private session on November 6, 2026 and authorized main integration and push. Accepted preview: `0.11.24.6.a5fb088`; no public product release has been issued.

## November 6, 2026 — Android extension compatibility and integrated candidate

**Accepted candidate documentation · no public product release**

- Signed candidate `0.19.4-aex.119` (code 137) combines the previously accepted reader-startup optimizations and recorder-free delivery build with newer-extension coroutine compatibility. Recognized interface-linkage failures remain local to the affected source, with available extension/version diagnostics; healthy sources can continue.
- [Integrated source `41ca3eb2e5`](https://github.com/AltairCardinal/mihon-desktop/commit/41ca3eb2e50f25bd8fd70bac504cec2b1cebb7b5). The candidate passed 11 focused tests, signed-artifact checks and API36 emulator upgrade/startup checks. This does not establish acceptance on the reporting Lenovo Android17 device, universal future-extension compatibility, or a new performance measurement. The separate crash-log export failure is outside this fix.

Acceptance basis: the project owner accepted this delivered Android candidate on November 6, 2026 in a private conversation and authorized main integration, commit and push. No product release or APK upload is implied.

## November 6, 2026 — Android downloaded-chapter opening

The project owner accepted opening latency in signed Android tablet candidate `0.19.4-aex.118` (code 136, source `4f311f611b`). This integrates the B9 startup optimizations, including publishing page lists ahead of whole-chapter header reads, reusing unchanged facts and chapter lookups, and avoiding unrelated brightness-overlay updates. The accepted signed build omits the reader timing recorder. The unadopted Compose rc01 experiment is excluded. Acceptance is limited to the tested Android opening experience.

This records candidate acceptance and source integration. A public product release has not been issued.

## November 2, 2026 — Windows page-image retry

**Accepted candidate documentation · no public product release**

- Retrying the failed image in the accepted online two-page case restores its pixels and clears the error, preserving the normal partner and current page. Image retry is separate from saved-pairing recovery.
- Accepted Windows preview: `0.11.24.6.5007f5a`; source `5007f5a83d`, reader fix `a623b48880`, and online test-source address-refresh fix `63cef8f1cc`. This does not extend manual acceptance to Android or macOS, guarantee unavailable sources will recover, or publish a product release.

Acceptance basis: the project owner confirmed image recovery, error removal, partner stability and unchanged page on November 2, 2026, in a private conversation.

## November 1, 2026 — Windows global search focus and caret

**Accepted behavior documentation · Not a public product release**

- Global search focuses its input on entry. Prefilled keywords keep history closed and place the caret at the end; typing appends to the original text.
- Explicit clicks can open history, empty-query entry shows existing history, and manual caret positions and selections survive refocusing and returning from details. Result updates do not steal focus; history replacements position the caret at the end.
- Accepted Windows preview: `0.11.24.6.928f3fe`. Scoped main implementations: [entry focus](https://github.com/AltairCardinal/mihon-desktop/commit/5ac48de9f5fdb9b3b7e681e7925be92a73369800), [prefilled history behavior](https://github.com/AltairCardinal/mihon-desktop/commit/468aa18776a0fab37e722ab60769bd3495469056), and [caret and selection retention](https://github.com/AltairCardinal/mihon-desktop/commit/daf18c82430c7b72be05ba8f3bcc9ac2f0d3c7e2). This acceptance does not cover Android or macOS and does not publish a product release.

Acceptance basis: the project owner confirmed the Windows global-search behavior on November 1, 2026, in a private conversation and authorized main integration and push.

## November 1, 2026 — Accepted reader fixes, ripple choices and private feedback attachments

**Accepted candidate documentation · no public product release**

- Android two-page original tiles remain joined in the verified odd-size/resize cases. Single/two-page ripple keeps Classic (stepped), adds Scheme A (smooth), and retains Clean (no shadow). Reading-mode summaries and matching controls have corrected placement and theme behavior.
- Windows immediate single/two-page turns redraw prepared pages correctly; matching-toggle continuity is retained.
- Android/Windows feedback adds category templates, optional default-selected device summaries, private JPEG screenshots with processing/preview/removal, and explicitly enabled single-category temporary diagnostics. Public text/device summary and private attachments remain distinguished.
- Accepted candidates: Android `0.19.4-aex.115` (133, verified source `8b16f1d61c`) and Windows `0.11.24.6.5dd165c`. Android passed real 132→133 upgrade and 12 emulator pixel cases; Windows focused page-turn checks passed 50/50. macOS and broad performance claims are outside this acceptance. An automated Windows headless shutdown timeout remains recorded separately.

Acceptance basis: the project owner accepted this delivered scope in private chat on November 1, 2026 and authorized main integration, commit and push. No product release or artifact upload is implied.

## November 1, 2026 — Reader settings and batch read status

**Accepted candidate documentation · no public product release**

- Android Default/Automatic reading descriptions use consistent horizontal spacing and theme colors. The two-page Auto Spread Matching setting sits before Rotation without the extra Pager heading, preserves its saved state, and stays hidden in single-page and Webtoon modes.
- Windows reader settings use the corrected Simplified/Traditional Chinese scaling labels and omit the zoom shortcut bar while retaining existing viewport zoom controls. Normal selected/below-position read-status changes use one batch write, preserving sync intent and accurate failure handling.
- Accepted candidates: Windows `0.11.24.6.feaf073` and Android `0.19.4-aex.114` (versionCode `132`). [Reader implementation](https://github.com/AltairCardinal/mihon-desktop/commit/e38257ab357c57d069776154032918c479c37e1c); [candidate version and build record](https://github.com/AltairCardinal/mihon-desktop/commit/60fd190521cbe1097e2721725e0f7d2ec26d01d1). The Windows candidate also includes the separately documented batch-bookmark fix.
- The Android candidate was installed on the owner's tablet with the existing application identity and certificate. This acceptance covers the delivered reader-setting and chapter-action scope; it does not establish macOS acceptance, a fixed performance target, or a public release.

Acceptance basis: the project owner accepted these candidates in private chat on November 1, 2026 and requested main integration, a commit, and a push.

## November 1, 2026 — Bounded Windows comic panning

**Accepted fix documentation · no public product release**

- Zoomed single-page and two-page reading limits extra blank space on each side to one third of the reading viewport's corresponding dimension. Two-page reading uses the whole spread; small images retain unavoidable margins.
- Panning stops at the boundary and follows reverse movement immediately. Zoom and window-size changes recalculate the boundary.
- Accepted implementation: `0913d0cc67d91206dd9f68450c1bc84c909dc596`; scoped remote-main integration: `35664bd168e591b7aefac87df1c694959e6e9371`. The relevant reader implementation and 37 passing focused tests are unchanged from the verified pre-integration patch. This acceptance covers Windows only; no new product version or package was allocated.

Acceptance basis: the project owner accepted the one-third blank-space limit in private chat on November 1, 2026 and authorized main integration, a commit, and a push.

## November 1, 2026 — Windows library context menu and transient download feedback

**Accepted candidate documentation · no public product release**

- Library context menus follow the clicked work and keep download actions available: next unread chapter and all unread chapters.
- Download feedback is a temporary bubble with the actual newly queued count, an explicit zero-addition reason when there are no unread chapters, and a distinct message for unread chapters already downloaded or queued. Category feedback and download errors remain available.
- Update results follow the update summary on the same row; narrow layouts keep the button accessible.
- Accepted Windows preview: `0.11.24.6.b11e547`, source `b11e5471272dfa5f2f4d6fefc74a6115eed71a32`. Scoped main implementations: [context menu](https://github.com/AltairCardinal/mihon-desktop/commit/b26b599054) and [download feedback](https://github.com/AltairCardinal/mihon-desktop/commit/fbcdfe5530). The zero-chapter wording was clarified during acceptance. No Android or macOS acceptance or product release is implied.

Acceptance basis: the project owner accepted this Windows library candidate in private chat on November 1, 2026 and requested main integration and a push.

## November 1, 2026 — Windows batch chapter bookmarks

**Accepted fix documentation · no public product release**

- Adding or removing chapter bookmarks from manga details now uses one batch write on the normal path instead of a transaction for every chapter. Mixed selections preserve existing bookmarks and add only missing ones.
- A failed batch falls back to individual retries with accurate results. Failed chapters remain selected; a late completion does not clear a newer selection, and successful operations retain quiet feedback.
- Accepted Windows preview: `0.11.24.6.feaf073` (preview ID `main-fd653d0e21824628b5f731322e2ad3d3`); implementation and package source: `feaf073e1139f0ae4920afdf620deafdfa9ae60a`. Nine focused model/repository/UI checks and the packaged extension runtime check passed.
- This acceptance covers Windows batch bookmarks only. No Android or macOS acceptance, fixed device timing, or new public product release is claimed.

Acceptance basis: the project owner accepted this Windows preview in private chat on November 1, 2026 and authorized main integration and a push.

## November 1, 2026 — Consistent Windows two-page pairing

**Accepted candidate documentation · no public product release**

- Forward and backward turns, jumps, and reopening preserve the same pairing at a chapter position in the accepted Windows reader case. The initial page slots follow the confirmed layout, while background matching preserves the visible page and its partner.
- Two-page and landscape reading, manual pairing, zoom, and reading progress remain available; automatic assistance still does not guarantee recognition of every scan or layout.
- Accepted Windows preview: `0.11.24.6.987389f`; implementation/source commit: `987389f88231236178ae824086b26bde37eb5dce`. Main integration: `769488a519646ac965d1cf763f1ef99772c1e278`.
- Shared and Android code integration was verified separately. This user acceptance covers the Windows preview; it does not establish Android device or macOS runtime acceptance, or a new Android release.

Acceptance basis: the project owner accepted this Windows preview in private chat on November 1, 2026, covering pairing across navigation and reopening, layout switching, zoom, and progress. No public product release was allocated.

## October 30, 2026 — Windows library category labels and wheel scrolling

**Accepted fix documentation · no public product release**

- The system default category now displays **默认** in Simplified Chinese and **default** in English instead of a blank label. Item counts retain the label.
- Hovering over the category bar lets the mouse wheel scroll it horizontally without selecting another category. Custom category names and the existing category visibility rules remain unchanged.
- Accepted Windows preview: `0.11.24.6.3026516` (preview ID `main-c30ef487baba4273a2819788493e1913`); accepted source: `30265162bf`; [integrated implementation](https://github.com/AltairCardinal/mihon-desktop/commit/3ebbe1cf28). Four focused interaction checks and the packaged extension runtime check passed. Acceptance covers this Windows category-bar path only.

Acceptance basis: the project owner explicitly accepted this candidate in private chat on October 30, 2026 and requested main integration and a push.

## October 30, 2026 — Windows sync connection and local decisions

**Accepted fix documentation · no public product release**

- The sync panel opens immediately, and existing or newly produced local conflicts remain visible while the initial connection check runs. Setup results expose pending items too.
- A single keep/cancel decision applies locally without a confirmation dialog, credential rereads, or waiting for the full sync-state refresh. Batch decisions retain confirmation; download batches awaiting system confirmation are distinct from manual choices.
- Active sync sessions renew renewable GitHub credentials instead of retaining an expired access token for the whole session. Revoked authorization still requires reconnecting.
- Accepted Windows preview: `0.11.24.6.5fbb6d9` (source `5fbb6d9582`); [integrated implementation](https://github.com/AltairCardinal/mihon-desktop/commit/0e9540b43b). Acceptance basis: project-owner confirmation on October 30, 2026 for these Windows sync fixes. This does not claim Android acceptance or a public release.

## October 30, 2026 — Space toggles the reader toolbars

**Accepted fix documentation · no public product release**

- Space now shows or hides both reader toolbars in every desktop reading mode instead of turning a page, including the reported previous-page behavior in right-to-left reading. The reader viewport must have keyboard focus.
- Original implementation: `597ffe15e2`; [main integration](https://github.com/AltairCardinal/mihon-desktop/commit/a22ee07e2f). The integration preserves the accepted patch unchanged.
- The existing 37 focused keyboard, focus, navigation and fullscreen tests passed. This acceptance covers Windows desktop reading; other platforms were not accepted as natively tested. No new product version or package was allocated.

Acceptance basis: the project owner explicitly accepted the reader shortcut in private chat on October 30, 2026 and requested main integration and a push.

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
