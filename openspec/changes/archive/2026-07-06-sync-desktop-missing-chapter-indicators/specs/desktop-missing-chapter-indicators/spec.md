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
