## Context

Mihon Android already builds manga detail chapter rows as a mixed list of chapter items and missing-chapter count items. The gap detection itself lives in shared domain code at `tachiyomi.domain.chapter.service.MissingChapters`, so Mihon Desktop does not need a new algorithm.

Mihon Desktop currently computes a `displayedChapters: List<Chapter>` inside `MangaDetailScreen` after applying read/unread, bookmarked, downloaded, scanlator, and sort state. The UI then renders only chapter rows. Desktop also has a library settings screen and app preference infrastructure, but no desktop UI for the original `pref_hide_missing_chapter_indicators` setting.

## Goals / Non-Goals

**Goals:**
- Reuse the existing shared missing-chapter gap calculation.
- Insert missing-chapter indicator rows into the desktop manga detail list after existing filter and sort behavior has produced the visible chapter sequence.
- Add a desktop settings control matching the original hide-missing-chapter-indicators preference.
- Keep chapter selection and batch actions scoped to real chapter rows only.
- Cover the list model, preference wiring, and visible UI rendering with tests.

**Non-Goals:**
- Do not change chapter number recognition, source refresh, download queue semantics, reader navigation, migration, or database schema.
- Do not infer or fetch missing chapters from sources.
- Do not add per-manga overrides; the setting is global like the original preference.

## Decisions

1. Reuse `calculateChapterGap` from `tachiyomi.domain.chapter.service`.

   Alternative considered: implement desktop-specific integer gap logic. That would duplicate domain behavior and risk drift from Android. Reusing the domain function keeps Android and Desktop semantics aligned for unknown numbers, specials, decimals, and floor-based gaps.

2. Introduce a desktop detail list UI model for rows.

   The desktop screen should convert the filtered/sorted `List<Chapter>` into a mixed list such as `MangaDetailChapterListRow.ChapterRow` and `MangaDetailChapterListRow.MissingCountRow`. This keeps selection, downloads, and reader navigation operating on `List<Chapter>` while the UI receives the extra display rows.

3. Insert indicators after filtering and sorting.

   Original Android behavior computes `processedChapters` first, then inserts missing-count separators. Desktop should match that user-visible behavior: filters and sort controls affect which gaps are visible in the current list.

4. Add a desktop preference and settings row for hiding indicators.

   The original preference key is `pref_hide_missing_chapter_indicators`; desktop should use the same key where practical to preserve semantics. The library settings screen is the closest desktop location because it already controls library update/display behavior.

5. Keep missing-count rows non-interactive.

   Missing indicators are informational separators. They must not be selectable, downloadable, bookmarkable, marked read/unread, or sent to reader navigation.

## Risks / Trade-offs

- Filtered views can show gaps that are caused by filters rather than source availability -> This matches Android's post-filter insertion behavior and will be covered in tests.
- Desktop settings currently use English hardcoded UI text in this area -> Add the new row consistently with existing desktop settings text rather than introducing a localization refactor.
- Existing dirty worktree contains archived reader changes -> Keep this change scoped to desktop library/settings files and OpenSpec artifacts; do not touch reader files.

## Migration Plan

No database migration is required. The new preference defaults to showing indicators, matching the original default where `hideMissingChapters=false`. Rolling back the UI change leaves the stored preference harmless.

## Open Questions

None. The user selected full parity with the original behavior, including the hide preference.
