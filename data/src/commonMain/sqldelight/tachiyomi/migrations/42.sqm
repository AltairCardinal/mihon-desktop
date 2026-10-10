CREATE TABLE sync_repair_failures (
    space_id TEXT NOT NULL,
    generation INTEGER NOT NULL,
    kind TEXT NOT NULL,
    target TEXT NOT NULL,
    evidence_id TEXT NOT NULL,
    path TEXT NOT NULL,
    reason TEXT NOT NULL,
    original TEXT NOT NULL,
    resolved INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(space_id, generation, kind, target, evidence_id)
);

-- Rejected data from released databases remains visible after the recovery UI is introduced.
INSERT INTO sync_repair_failures(space_id, generation, kind, target, evidence_id, path, reason, original)
SELECT space_id, generation, 'BATCH', batch_id, 'legacy-v42:' || batch_id, '',
    coalesce(error, 'sync batch rejected'), body_json
FROM sync_inbox_batches WHERE status = 'REJECTED';
