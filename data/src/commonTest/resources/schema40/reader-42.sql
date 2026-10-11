ALTER TABLE chapter_pairings ADD COLUMN opening_mode TEXT NOT NULL DEFAULT 'AUTO';
CREATE TABLE chapter_pairing_pairs (
    chapter_id INTEGER NOT NULL,
    page_ordinal INTEGER NOT NULL,
    PRIMARY KEY(chapter_id, page_ordinal),
    FOREIGN KEY(chapter_id) REFERENCES chapter_pairings(chapter_id) ON DELETE CASCADE
);
