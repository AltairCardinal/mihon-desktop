# Comet Design Handoff

- Change: sync-desktop-missing-chapter-indicators
- Phase: design
- Mode: compact
- Context hash: 2d843efc5e2fd509cd8a507965a33ae5ad11f32c713f71cee8f431bd2ef6fee2

Generated-by: comet-handoff.sh

OpenSpec remains the canonical capability spec. This handoff is a deterministic, source-traceable context pack, not an agent-authored summary.

## openspec/changes/sync-desktop-missing-chapter-indicators/proposal.md

- Source: openspec/changes/sync-desktop-missing-chapter-indicators/proposal.md
- Lines: 1-26
- SHA256: fd83cc2ea762430d5413046f68ed0c8e380dd3633061f1e68f9fc825af751361

```md
## Why

Mihon Android already detects gaps in recognized chapter numbers and shows missing-chapter indicators in the manga detail chapter list, but Mihon Desktop currently lists chapters without that continuity feedback. Desktop users can miss skipped or unavailable chapters unless they manually inspect chapter numbers.

## What Changes

- Show missing-chapter indicator rows in Mihon Desktop manga detail chapter lists when the currently displayed chapter sequence has gaps.
- Reuse the existing shared `tachiyomi.domain.chapter.service` gap calculation instead of introducing a desktop-only algorithm.
- Add a desktop setting matching the original Mihon preference to hide missing-chapter indicators.
- Apply the setting to manga detail rendering so indicators disappear when the user chooses to hide them.
- Keep reader navigation, downloads, migration, chapter refresh, and chapter recognition behavior unchanged.

## Capabilities

### New Capabilities
- `desktop-missing-chapter-indicators`: Desktop manga detail screens detect and display missing chapter gaps, with a user preference to hide the indicators.

### Modified Capabilities

None.

## Impact

- Affected modules: `app-desktop` UI, desktop preferences/settings, and desktop manga detail tests.
- Reused modules: `domain` chapter missing-gap service and existing chapter model/sort data.
- No database schema changes, new network endpoints, or new source APIs.
```

## openspec/changes/sync-desktop-missing-chapter-indicators/design.md

- Source: openspec/changes/sync-desktop-missing-chapter-indicators/design.md
- Lines: 1-55
- SHA256: 65b1e7ef40bfd49316fd2282d72bf2a5c12f03b404bc2ac85d6b297756dc0709

```md
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
```

## openspec/changes/sync-desktop-missing-chapter-indicators/tasks.md

- Source: openspec/changes/sync-desktop-missing-chapter-indicators/tasks.md
- Lines: 1-23
- SHA256: 2da76802d5061004c0f2f815fcfe8291a6ce8c5715f2bb3c63ff190d83d3661c

```md
## 1. Missing Indicator List Model

- [ ] 1.1 Add failing JVM tests for desktop missing-chapter row insertion, including middle gaps, leading gaps, unknown numbers, and sort direction.
- [ ] 1.2 Implement the desktop manga detail row model by reusing shared missing-chapter gap calculation.
- [ ] 1.3 Re-run row model tests and refactor naming or boundaries without changing behavior.

## 2. Preference and Settings Wiring

- [ ] 2.1 Add failing tests for the desktop hide-missing-chapter-indicators preference default and updates.
- [ ] 2.2 Add the desktop app preference and a Library settings checkbox using the original preference semantics.
- [ ] 2.3 Re-run preference/settings tests and refactor wiring if needed.

## 3. Manga Detail UI Integration

- [ ] 3.1 Add failing tests that the manga detail display model respects the hide setting and keeps chapter actions scoped to real chapters.
- [ ] 3.2 Render missing-chapter indicator rows in the desktop manga detail chapter list after filters and sorting.
- [ ] 3.3 Ensure select-all, batch actions, downloads, and reader navigation continue to operate only on real chapters.

## 4. Verification

- [ ] 4.1 Run targeted desktop JVM tests for manga detail list rows, settings preferences, and existing manga detail behavior.
- [ ] 4.2 Run formatting checks.
- [ ] 4.3 Run the desktop build/deploy script and record the deployed Mihon Desktop version.
```

## openspec/changes/sync-desktop-missing-chapter-indicators/specs/desktop-missing-chapter-indicators/spec.md

- Source: openspec/changes/sync-desktop-missing-chapter-indicators/specs/desktop-missing-chapter-indicators/spec.md
- Lines: 1-45
- SHA256: 8fdba1a389ab68f074ba8748dadf5beeb6923f1abb70c48f8e841dfd12cdc765

```md
## ADDED Requirements

### Requirement: Desktop manga detail shows missing chapter indicators
Mihon Desktop SHALL show informational missing-chapter indicator rows in the manga detail chapter list when recognized chapter numbers in the currently displayed sequence are not continuous.

#### Scenario: Gap between displayed chapters
- **WHEN** a manga detail list displays chapters 1, 2, and 5 in chapter-number order
- **THEN** the list shows an indicator that 2 chapters are missing between chapters 2 and 5

#### Scenario: Gap before first displayed chapter
- **WHEN** a manga detail list starts at recognized chapter number 3
- **THEN** the list shows an indicator that 2 chapters are missing before chapter 3

#### Scenario: Unknown or special chapter numbers
- **WHEN** displayed chapters have unknown or negative chapter numbers
- **THEN** those chapters do not create missing-chapter indicators

### Requirement: Missing indicators respect current filters and sorting
Mihon Desktop SHALL calculate missing-chapter indicators from the same filtered and sorted chapter sequence that is rendered to the user.

#### Scenario: Filters change visible gaps
- **WHEN** the user changes read, unread, bookmarked, downloaded, or scanlator filters
- **THEN** missing-chapter indicators update to match the resulting visible chapter sequence

#### Scenario: Sort direction changes indicator placement
- **WHEN** the user changes chapter sort order or direction
- **THEN** missing-chapter indicators appear between the appropriate adjacent displayed chapters for that order

### Requirement: User can hide missing chapter indicators
Mihon Desktop SHALL provide a library setting that hides missing-chapter indicator rows when enabled.

#### Scenario: Hide setting enabled
- **WHEN** the user enables the hide missing-chapter indicators setting
- **THEN** manga detail chapter lists show only real chapter rows even when displayed chapter numbers have gaps

#### Scenario: Hide setting disabled
- **WHEN** the user disables the hide missing-chapter indicators setting
- **THEN** manga detail chapter lists show missing-chapter indicator rows for detected gaps

### Requirement: Missing indicators are informational only
Missing-chapter indicator rows SHALL NOT participate in chapter actions.

#### Scenario: Selection and actions skip indicators
- **WHEN** a manga detail list contains missing-chapter indicators
- **THEN** selecting all, marking read, bookmarking, downloading, deleting, and opening reader chapters operate only on real chapter rows
```
