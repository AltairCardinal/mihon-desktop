ALTER TABLE reports ADD COLUMN protocol_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE reports ADD COLUMN category_id TEXT;
ALTER TABLE reports ADD COLUMN screenshot_consent INTEGER NOT NULL DEFAULT 0 CHECK (screenshot_consent IN (0, 1));
CREATE TABLE screenshot_budget (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    bytes_used INTEGER NOT NULL DEFAULT 0 CONSTRAINT screenshot_capacity CHECK (bytes_used BETWEEN 0 AND 33554432)
);
INSERT INTO screenshot_budget(singleton, bytes_used) VALUES (1, 0);
CREATE TABLE report_screenshots (
    submission_id TEXT NOT NULL REFERENCES reports(submission_id) ON DELETE CASCADE,
    attachment_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal IN (0, 1)),
    mime TEXT NOT NULL CHECK (mime = 'image/jpeg'),
    width INTEGER NOT NULL CHECK (width BETWEEN 1 AND 1920),
    height INTEGER NOT NULL CHECK (height BETWEEN 1 AND 1920),
    byte_length INTEGER NOT NULL CHECK (byte_length BETWEEN 1 AND 524288),
    sha256 TEXT NOT NULL CHECK (length(sha256) = 64),
    created_at INTEGER NOT NULL,
    data BLOB NOT NULL CHECK (length(data) = byte_length),
    PRIMARY KEY (submission_id, attachment_id),
    UNIQUE (submission_id, ordinal)
);
CREATE TRIGGER screenshot_budget_insert AFTER INSERT ON report_screenshots BEGIN
    UPDATE screenshot_budget SET bytes_used = bytes_used + NEW.byte_length WHERE singleton = 1;
END;
CREATE TRIGGER screenshot_budget_delete AFTER DELETE ON report_screenshots BEGIN
    UPDATE screenshot_budget SET bytes_used = bytes_used - OLD.byte_length WHERE singleton = 1;
END;
