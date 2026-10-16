CREATE TABLE reports (
    submission_id TEXT PRIMARY KEY,
    content_hash TEXT NOT NULL,
    receipt TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    summary TEXT NOT NULL,
    ip_key TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CLAIMED', 'SENT', 'REVIEW')),
    next_attempt INTEGER NOT NULL DEFAULT 0,
    claimed_at INTEGER,
    issue_url TEXT
);
CREATE INDEX reports_ip_created ON reports(ip_key, created_at);
CREATE INDEX reports_created ON reports(created_at);
CREATE INDEX reports_dispatch ON reports(status, next_attempt);
