CREATE TABLE feature_votes (
    feature_id TEXT NOT NULL,
    voter_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (feature_id, voter_hash)
);
