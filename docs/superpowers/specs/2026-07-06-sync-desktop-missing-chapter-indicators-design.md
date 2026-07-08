---
comet_change: sync-desktop-missing-chapter-indicators
role: technical-design
canonical_spec: openspec
archived-with: 2026-07-06-sync-desktop-missing-chapter-indicators
status: final
---

# Sync Desktop Missing Chapter Indicators Design

## Context

Mihon Android already detects missing chapters in manga detail lists by inserting informational rows between visible chapter rows. The reusable logic for gap calculation already exists in `domain/src/commonMain/kotlin/tachiyomi/domain/chapter/service/MissingChapters.kt`.

Mihon Desktop currently renders `MangaDetailScreen` from a filtered and sorted `List<Chapter>`. That list drives both visible rows and real chapter actions such as selection, downloads, reader navigation, marking read, and bookmarking. Desktop also has `DesktopAppPreferences` and `LibrarySettingsScreen`, but no desktop UI for the original `pref_hide_missing_chapter_indicators` preference.

## Design

The implementation will introduce a desktop-only display row model beside the existing manga detail helpers:

- `MangaDetailChapterListRow.ChapterRow(chapter: Chapter)`
- `MangaDetailChapterListRow.MissingCountRow(id: String, count: Int)`

The row builder consumes the already filtered and sorted chapter list and returns mixed display rows. It computes leading gaps from chapter 1 to the first recognized visible chapter, and adjacent gaps with the existing `calculateChapterGap` domain function. The builder accounts for desktop sort direction by pairing lower/higher chapter numbers the same way Android does before calculating gaps.

`MangaDetailScreen` keeps all chapter actions based on `displayedChapters: List<Chapter>`. Only the `LazyColumn` rendering path receives the mixed row list. This keeps indicator rows non-interactive and avoids changing selection, batch actions, downloads, deletion, or reader navigation.

`DesktopAppPreferences` will expose a boolean preference using the original key `pref_hide_missing_chapter_indicators`, defaulting to `false`. `LibrarySettingsScreen` will add a checkbox in the library settings UI. `MangaDetailScreen` will observe this preference and skip row insertion when it is enabled.

## Alternatives Considered

Implementing a desktop-only gap algorithm was rejected because the shared domain service already captures Mihon semantics for unknown numbers, specials, decimals, and floor-based gaps.

Adding a per-manga setting was rejected because the original behavior is a global library preference. Matching the original reduces user surprise and avoids new database or flag wiring.

Embedding missing indicators directly into the raw chapter list was rejected because it would blur display rows with real chapters and increase risk around selection and chapter actions.

## Testing Strategy

Use TDD for every behavior change.

First, add JVM tests for the row builder:

- chapters 1, 2, 5 produce a missing count of 2 between 2 and 5
- first visible chapter 3 produces a leading missing count of 2
- unknown or negative chapter numbers do not create indicators
- ascending and descending order place indicators between the correct visible rows

Second, add preference tests around `DesktopAppPreferences`:

- the hide preference defaults to `false`
- setting it to `true` is persisted and observable

Third, add manga detail display tests:

- row building is skipped when the hide preference is enabled
- extracted/selectable chapter IDs come only from real chapter rows

Finally, run targeted desktop JVM tests, `./gradlew spotlessCheck`, and `./scripts/build-desktop.sh`.

## User-Visible Behavior

Users open a manga detail page normally. If the visible chapter sequence has missing recognized chapter numbers, the chapter list shows a muted separator row such as “Missing 2 chapters” at the relevant position. The row has no click, selection, or action affordance.

Users can go to Library settings and enable the hide missing-chapter indicators option. After enabling it, manga detail pages show only chapter rows. Disabling the option shows indicators again.

Empty chapter lists, loading state, refresh errors, source errors, and unknown chapter numbers retain current behavior.

## Risks

Filtered lists can show gaps caused by filters rather than actual source availability. This matches the original post-filter insertion behavior and will be documented by tests.

Desktop settings text in this area is currently hardcoded English. This change follows that local pattern and does not introduce a broader localization refactor.
