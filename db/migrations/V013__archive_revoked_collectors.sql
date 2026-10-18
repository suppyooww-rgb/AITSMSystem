BEGIN;

ALTER TABLE monitoring_collectors
  ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS monitoring_collectors_archived_at_idx
  ON monitoring_collectors(archived_at);

COMMIT;
