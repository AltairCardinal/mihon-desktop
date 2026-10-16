-- Private crash-log columns are added without rewriting existing reports.
ALTER TABLE reports ADD COLUMN crash_log TEXT NOT NULL DEFAULT '';
ALTER TABLE reports ADD COLUMN crash_log_consent INTEGER NOT NULL DEFAULT 0 CHECK (crash_log_consent IN (0, 1));
