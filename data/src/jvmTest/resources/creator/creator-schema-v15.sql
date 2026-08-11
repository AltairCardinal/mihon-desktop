PRAGMA foreign_keys = ON;

CREATE TABLE creators(
    _id INTEGER NOT NULL PRIMARY KEY,
    display_name TEXT NOT NULL,
    normalized_name TEXT NOT NULL UNIQUE,
    sort_name TEXT,
    aliases TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    last_modified_at INTEGER NOT NULL
);

CREATE TABLE manga_creators(
    manga_id INTEGER NOT NULL,
    creator_id INTEGER NOT NULL,
    role TEXT NOT NULL,
    source_text TEXT,
    confidence REAL NOT NULL,
    evidence TEXT NOT NULL,
    PRIMARY KEY(manga_id, creator_id, role)
);

CREATE TABLE discovery_candidate_creators(
    candidate_id INTEGER NOT NULL,
    creator_id INTEGER NOT NULL,
    role TEXT NOT NULL,
    source_text TEXT,
    confidence REAL NOT NULL,
    evidence TEXT NOT NULL,
    PRIMARY KEY(candidate_id, creator_id, role)
);

CREATE TABLE creator_watches(
    creator_id INTEGER NOT NULL PRIMARY KEY,
    enabled INTEGER NOT NULL,
    source_ids TEXT NOT NULL,
    language_tags TEXT NOT NULL,
    last_checked_at INTEGER,
    last_success_at INTEGER,
    last_error TEXT,
    created_at INTEGER NOT NULL
);

CREATE TABLE canonical_works(
    _id INTEGER NOT NULL PRIMARY KEY,
    primary_title TEXT NOT NULL,
    normalized_title TEXT NOT NULL,
    primary_creator_id INTEGER,
    original_language TEXT,
    created_at INTEGER NOT NULL,
    last_modified_at INTEGER NOT NULL
);

CREATE TABLE manga_work_matches(
    manga_id INTEGER NOT NULL UNIQUE,
    work_id INTEGER NOT NULL,
    confidence REAL NOT NULL,
    match_reason TEXT NOT NULL,
    state TEXT NOT NULL,
    manually_confirmed INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    last_modified_at INTEGER NOT NULL
);

CREATE TABLE discovery_candidates(
    _id INTEGER NOT NULL PRIMARY KEY,
    source INTEGER NOT NULL,
    url TEXT NOT NULL,
    title TEXT NOT NULL,
    normalized_title TEXT NOT NULL,
    author_text TEXT,
    artist_text TEXT,
    language_tag TEXT NOT NULL,
    language_confidence REAL NOT NULL,
    language_evidence TEXT NOT NULL,
    thumbnail_url TEXT,
    first_seen_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    details_fetched_at INTEGER,
    state TEXT NOT NULL,
    UNIQUE(source, url)
);

PRAGMA user_version = 15;
