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
