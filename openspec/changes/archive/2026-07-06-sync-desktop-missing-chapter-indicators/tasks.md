## 1. Missing Indicator List Model

- [x] 1.1 Add failing JVM tests for desktop missing-chapter row insertion, including middle gaps, leading gaps, unknown numbers, and sort direction.
- [x] 1.2 Implement the desktop manga detail row model by reusing shared missing-chapter gap calculation.
- [x] 1.3 Re-run row model tests and refactor naming or boundaries without changing behavior.

## 2. Preference and Settings Wiring

- [x] 2.1 Add failing tests for the desktop hide-missing-chapter-indicators preference default and updates.
- [x] 2.2 Add the desktop app preference and a Library settings checkbox using the original preference semantics.
- [x] 2.3 Re-run preference/settings tests and refactor wiring if needed.

## 3. Manga Detail UI Integration

- [x] 3.1 Add failing tests that the manga detail display model respects the hide setting and keeps chapter actions scoped to real chapters.
- [x] 3.2 Render missing-chapter indicator rows in the desktop manga detail chapter list after filters and sorting.
- [x] 3.3 Ensure select-all, batch actions, downloads, and reader navigation continue to operate only on real chapters.

## 4. Verification

- [x] 4.1 Run targeted desktop JVM tests for manga detail list rows, settings preferences, and existing manga detail behavior.
- [x] 4.2 Run formatting checks.
- [x] 4.3 Run the desktop build/deploy script and record the deployed Mihon Desktop version.
