---
title: Changelog
description: A traceable history of public feature documentation, separate from the current feature guide.
---

# Changelog

Read [Current features](./features.md) for what the project currently documents. This page records changes to that baseline; it does not accumulate full copies of the guide.

Public product releases and documentation updates are different events. A date here is not a release version. Implementation work enters the public feature description only after explicit project-owner acceptance.

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
