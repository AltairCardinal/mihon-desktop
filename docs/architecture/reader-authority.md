# Reader authority and fork-added pairing

This document records the authority boundary for reader behavior after the 2026-07-18 provenance correction. The fixed original Mihon authority is `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`. The current `app/` tree is a fork consumer and must not be used as proof of original behavior merely because it builds for Android.

## Fixed-main behavior

The fixed original implements one adapter item or holder per `ReaderPage`. It provides the following comparable rules:

- `PagerViewerAdapter.setChapters` and `PagerViewers`: LTR/vertical ordering, R2L reversal, navigation direction, and explicit chapter-transition items.
- `PagerPageHolder.process` and `splitInHalf`: rotate-to-fit and splitting one wide source page into two displayed halves.
- `WebtoonAdapter.setChapters` and `WebtoonPageHolder.process`: one item per source page, explicit transitions, and split/merge or rotation within one wide-page holder.
- `ImageUtil.isWideImage` and `splitInHalf`: the historical wide-image rule and half-image extraction.

The repository-owned fixed-main inventory pins these paths and blob IDs in `app-desktop/src/test/resources/parity/fixed-main-path-inventory.json`; runtime tests do not depend on Git history being available.

## Fork-only pairing evidence

The following files are absent from the fixed original ref and were first introduced by fork commit `bef51fc6924c6a9de185fa0fb2a56ce76309dc19`:

- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagePairingAlgorithm.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PairingState.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/DualPageViewerAdapter.kt`
- `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/DualPagePairingTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/DualPageViewerAdapterTest.kt`

For each path, `git cat-file -e 6fbf6dfca203d99d6dd32137f2df97ced40c81b8:<path>` reports absence. The production classes and both contract tests are therefore current-fork evidence, not original-Mihon evidence.

## Classification

- Adjacent portrait-page pairing in `ReaderPagePairing` and `ReaderPairingState` is a fork-added cross-platform product enhancement shared by the current Android and Desktop consumers.
- Desktop cover-single, manual spread adjustment, edge matching, and landscape-parity options are Desktop product enhancements layered explicitly through `PagePairingOptions`.
- Preserving the center pixel when splitting odd-width images is an explicit correctness fix relative to historical `ImageUtil.splitInHalf` behavior.
- Android View/Bitmap/Coil code and Desktop Compose/Skia code remain platform adapters.

These classifications preserve current dual-page behavior. They do not create an original-Mihon pairing default that does not exist.

## Maintenance boundary

When reader behavior changes:

1. Compare page order, reading direction, wide-page split/rotation, and chapter transitions against the pinned fixed-main paths before changing shared semantics.
2. Do not list any fork-only pairing path as an `upstreamSymbol` in parity manifest ID 43. The provenance contract rejects every path listed above even if a synthetic inventory contains it.
3. Keep adjacent portrait pairing labeled as a fork-added shared enhancement in test names and evidence. Android/Desktop consistency proves shared wiring, not original authority.
4. Keep Desktop-specific options explicit and verify they do not silently change the current Android consumer defaults.
5. Update this document, parity manifest ID 43, its fixed-main inventory, and behavior tests together when the authority boundary changes.
