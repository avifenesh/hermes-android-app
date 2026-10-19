-- Dedicated report database: do not share with other applications.
CREATE TABLE IF NOT EXISTS reports (
  id TEXT PRIMARY KEY NOT NULL,
  text TEXT NOT NULL CHECK (length(CAST(text AS BLOB)) BETWEEN 1 AND 8192),
  received_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
) STRICT;
-- Defense in depth: even accidental direct INSERTs cannot exceed the queue budget.
-- Existing IDs must reach the uniqueness handler so retries still work at capacity.
CREATE TRIGGER IF NOT EXISTS reports_capacity
BEFORE INSERT ON reports
WHEN NOT EXISTS (SELECT 1 FROM reports WHERE id = NEW.id)
 AND (SELECT count(*) FROM reports) >= 1000
BEGIN
  SELECT RAISE(ABORT, 'report queue full');
END;
