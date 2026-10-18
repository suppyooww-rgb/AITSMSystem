-- Repair databases that were baselined at V016 before the release schema was
-- actually applied. Every statement is idempotent so databases that received
-- the original migrations remain unchanged.

ALTER TABLE monitoring_collectors
  ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS ai_bridge_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS ai_bridge_available BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS ai_bridge_last_seen_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS ai_bridge_model VARCHAR(150),
  ADD COLUMN IF NOT EXISTS ai_bridge_last_error VARCHAR(500);

CREATE INDEX IF NOT EXISTS monitoring_collectors_archived_at_idx
  ON monitoring_collectors(archived_at);
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

ALTER TABLE ticket_history
  ADD COLUMN IF NOT EXISTS previous_status VARCHAR(30),
  ADD COLUMN IF NOT EXISTS work_note TEXT,
  ADD COLUMN IF NOT EXISTS user_message TEXT,
  ADD COLUMN IF NOT EXISTS resolution_category VARCHAR(80),
  ADD COLUMN IF NOT EXISTS notify_requester BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE ticket_history
SET work_note = 'Legacy status change recorded before transition notes were enabled.'
WHERE work_note IS NULL;

ALTER TABLE tickets
  ADD COLUMN IF NOT EXISTS assigned_to_user_id INT REFERENCES users(id) ON DELETE SET NULL;

UPDATE tickets t
SET assigned_to_user_id = u.id
FROM users u
WHERE t.assigned_to_user_id IS NULL
  AND t.assigned_to IS NOT NULL
  AND (
    lower(t.assigned_to) = lower(u.full_name)
    OR lower(t.assigned_to) = lower(u.email)
    OR lower(t.assigned_to) LIKE lower(u.full_name) || ' (%)'
  )
  AND 1 = (
    SELECT count(*)
    FROM users candidate
    WHERE lower(t.assigned_to) = lower(candidate.full_name)
       OR lower(t.assigned_to) = lower(candidate.email)
       OR lower(t.assigned_to) LIKE lower(candidate.full_name) || ' (%)'
  );

CREATE INDEX IF NOT EXISTS tickets_assigned_to_user_id_idx
  ON tickets(assigned_to_user_id);
CREATE INDEX IF NOT EXISTS notifications_user_created_idx
  ON notifications(user_id, created_at DESC)
  WHERE type <> 'direct_message';
