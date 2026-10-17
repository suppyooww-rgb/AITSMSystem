BEGIN;

ALTER TABLE monitoring_collectors
  ADD COLUMN IF NOT EXISTS ai_bridge_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS ai_bridge_available BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS ai_bridge_last_seen_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS ai_bridge_model VARCHAR(150),
  ADD COLUMN IF NOT EXISTS ai_bridge_last_error VARCHAR(500);

CREATE INDEX IF NOT EXISTS monitoring_collectors_ai_bridge_enabled_idx
  ON monitoring_collectors(ai_bridge_enabled);
CREATE INDEX IF NOT EXISTS monitoring_collectors_ai_bridge_last_seen_idx
  ON monitoring_collectors(ai_bridge_last_seen_at);

CREATE TABLE IF NOT EXISTS ai_bridge_jobs (
  id UUID PRIMARY KEY,
  collector_id UUID REFERENCES monitoring_collectors(id) ON DELETE SET NULL,
  status VARCHAR(20) NOT NULL,
  model VARCHAR(150) NOT NULL,
  request_payload TEXT NOT NULL,
  response_payload TEXT,
  error_message VARCHAR(500),
  created_at TIMESTAMP NOT NULL,
  claimed_at TIMESTAMP,
  completed_at TIMESTAMP,
  expires_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS ai_bridge_jobs_status_idx ON ai_bridge_jobs(status);
CREATE INDEX IF NOT EXISTS ai_bridge_jobs_collector_idx ON ai_bridge_jobs(collector_id);
CREATE INDEX IF NOT EXISTS ai_bridge_jobs_created_idx ON ai_bridge_jobs(created_at);
CREATE INDEX IF NOT EXISTS ai_bridge_jobs_expires_idx ON ai_bridge_jobs(expires_at);

DO $$
DECLARE runtime_role TEXT;
BEGIN
  FOREACH runtime_role IN ARRAY ARRAY['aitsm_beta_runtime', 'aitsm_runtime'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = runtime_role) THEN
      EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE ai_bridge_jobs TO %I', runtime_role);
    END IF;
  END LOOP;
END $$;

COMMIT;
