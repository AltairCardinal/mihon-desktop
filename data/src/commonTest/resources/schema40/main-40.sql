CREATE TABLE chapter_url_aliases (
    chapter_id INTEGER NOT NULL REFERENCES chapters(_id) ON DELETE CASCADE,
    manga_id INTEGER NOT NULL REFERENCES mangas(_id) ON DELETE CASCADE,
    url TEXT NOT NULL,
    canonical_url TEXT NOT NULL,
    PRIMARY KEY(manga_id, url),
    CHECK(length(url) > 0 AND length(canonical_url) > 0)
);

CREATE INDEX chapter_url_alias_chapter ON chapter_url_aliases(chapter_id);

CREATE TRIGGER consistent_chapter_url_alias_insert BEFORE INSERT ON chapter_url_aliases
WHEN NOT EXISTS(SELECT 1 FROM chapters WHERE _id = new.chapter_id AND manga_id = new.manga_id)
OR EXISTS(SELECT 1 FROM chapter_url_aliases WHERE chapter_id = new.chapter_id AND canonical_url != new.canonical_url)
BEGIN
    SELECT RAISE(ABORT, 'Inconsistent chapter URL identity');
END;

CREATE TRIGGER consistent_chapter_url_alias_update BEFORE UPDATE ON chapter_url_aliases
WHEN NOT EXISTS(SELECT 1 FROM chapters WHERE _id = new.chapter_id AND manga_id = new.manga_id)
OR EXISTS(SELECT 1 FROM chapter_url_aliases WHERE chapter_id = new.chapter_id AND canonical_url != new.canonical_url)
BEGIN
    SELECT RAISE(ABORT, 'Inconsistent chapter URL identity');
END;


CREATE TABLE chapter_id_floor (
    singleton INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1),
    value INTEGER NOT NULL CHECK (value >= 0)
);

INSERT INTO chapter_id_floor(singleton, value) SELECT 1, coalesce(max(_id), 0) FROM chapters;

CREATE TRIGGER chapter_id_floor_insert_guard BEFORE INSERT ON chapters
WHEN NOT EXISTS (SELECT 1 FROM chapter_id_floor WHERE singleton = 1)
BEGIN
    SELECT RAISE(ABORT, 'Missing chapter identity floor');
END;

CREATE TRIGGER chapter_id_floor_delete_guard BEFORE DELETE ON chapters
WHEN NOT EXISTS (SELECT 1 FROM chapter_id_floor WHERE singleton = 1)
BEGIN
    SELECT RAISE(ABORT, 'Missing chapter identity floor');
END;

CREATE TRIGGER chapter_id_floor_insert AFTER INSERT ON chapters
BEGIN
    UPDATE chapter_id_floor SET value = max(value, new._id) WHERE singleton = 1;
END;

CREATE TRIGGER chapter_id_floor_delete AFTER DELETE ON chapters
BEGIN
    UPDATE chapter_id_floor SET value = max(value, old._id) WHERE singleton = 1;
END;

CREATE TABLE chapter_directory_phases (
    manga_id INTEGER NOT NULL PRIMARY KEY REFERENCES mangas(_id) ON DELETE CASCADE,
    phase_id TEXT NOT NULL UNIQUE,
    payload TEXT NOT NULL
);
