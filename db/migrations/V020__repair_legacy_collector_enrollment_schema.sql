-- Finish repairing legacy production databases that have the original
-- monitoring tables but missed the later ticket and collector enrollment work.

ALTER TABLE tickets
  ADD COLUMN IF NOT EXISTS reviewed_resolution TEXT,
  ADD COLUMN IF NOT EXISTS resolution_updated_by VARCHAR(150),
  ADD COLUMN IF NOT EXISTS resolution_updated_at TIMESTAMP;

ALTER TABLE monitoring_collectors
  ADD COLUMN IF NOT EXISTS scope VARCHAR(20) NOT NULL DEFAULT 'LAN',
  ADD COLUMN IF NOT EXISTS owner_user_id INTEGER REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS monitoring_collectors_owner_user_id_idx
  ON monitoring_collectors(owner_user_id);

CREATE TABLE IF NOT EXISTS collector_enrollment_codes (
  id UUID PRIMARY KEY,
  code_hash VARCHAR(64) NOT NULL UNIQUE,
  requested_by INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  scope VARCHAR(20) NOT NULL,
  site_id VARCHAR(100) NOT NULL,
  name VARCHAR(150) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  redeemed_at TIMESTAMP,
  collector_id UUID REFERENCES monitoring_collectors(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS collector_enrollment_codes_requested_by_idx ON collector_enrollment_codes(requested_by);
CREATE INDEX IF NOT EXISTS collector_enrollment_codes_expires_at_idx ON collector_enrollment_codes(expires_at);
CREATE INDEX IF NOT EXISTS collector_enrollment_codes_redeemed_at_idx ON collector_enrollment_codes(redeemed_at);
