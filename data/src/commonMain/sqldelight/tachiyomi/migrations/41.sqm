ALTER TABLE sync_runtime_pause_clock ADD COLUMN planned_at INTEGER;
ALTER TABLE sync_runtime_pause_clock ADD COLUMN planned_paused_millis INTEGER NOT NULL DEFAULT 0 CHECK(planned_paused_millis >= 0);

-- Older frozen plans do not record the end of counting; preserve their original clock.
INSERT OR IGNORE INTO sync_runtime_pause_clock(run_id)
SELECT run_id FROM sync_runtime_runs
WHERE EXISTS (
    SELECT 1 FROM sync_runtime_confirmations
    WHERE sync_runtime_confirmations.run_id = sync_runtime_runs.run_id
        AND direction = 'PLAN' AND batch_id = 'round'
);
UPDATE sync_runtime_pause_clock
SET planned_at = (SELECT created_at FROM sync_runtime_runs WHERE sync_runtime_runs.run_id = sync_runtime_pause_clock.run_id)
WHERE EXISTS (
    SELECT 1 FROM sync_runtime_confirmations
    WHERE sync_runtime_confirmations.run_id = sync_runtime_pause_clock.run_id
        AND direction = 'PLAN' AND batch_id = 'round'
);
