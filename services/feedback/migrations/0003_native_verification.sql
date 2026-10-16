-- Native verification metadata contains no report or log contents.
CREATE TABLE native_sessions (
    session_id TEXT PRIMARY KEY,
    secret_hash TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    verified INTEGER NOT NULL DEFAULT 0 CHECK (verified IN (0, 1)),
    used_submission_id TEXT
);
CREATE INDEX native_sessions_expiry ON native_sessions(expires_at);
-- Quotas must survive ten-minute session expiry; metadata lasts at most 24h during normal cleanup.
CREATE TABLE native_session_events (
    session_id TEXT PRIMARY KEY,
    ip_key TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX native_events_ip_created ON native_session_events(ip_key, created_at);
CREATE INDEX native_events_created ON native_session_events(created_at);
