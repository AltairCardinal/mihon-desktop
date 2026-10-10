CREATE TABLE IF NOT EXISTS sync_runtime_pause_clock (
    run_id TEXT NOT NULL PRIMARY KEY REFERENCES sync_runtime_runs(run_id) ON DELETE CASCADE,
    paused_millis INTEGER NOT NULL DEFAULT 0 CHECK(paused_millis >= 0),
    paused_at INTEGER
);

CREATE TRIGGER IF NOT EXISTS sync_runtime_pause_transition
AFTER UPDATE OF state ON sync_runtime_runs
WHEN old.state != new.state AND (old.state = 'PAUSED_USER' OR new.state = 'PAUSED_USER')
BEGIN
    INSERT OR IGNORE INTO sync_runtime_pause_clock(run_id) VALUES (new.run_id);
    UPDATE sync_runtime_pause_clock SET
        paused_millis = paused_millis + CASE WHEN old.state = 'PAUSED_USER'
            THEN MAX(0, new.updated_at - COALESCE(paused_at, old.updated_at)) ELSE 0 END,
        paused_at = CASE WHEN new.state = 'PAUSED_USER' THEN new.updated_at ELSE NULL END
    WHERE run_id = new.run_id;
END;

INSERT OR IGNORE INTO sync_runtime_pause_clock(run_id, paused_millis, paused_at)
SELECT run_id, 0, updated_at FROM sync_runtime_runs WHERE state = 'PAUSED_USER';
