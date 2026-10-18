-- Complete the pre-V009 schema that was incorrectly marked as baseline in
-- production. Statements are idempotent and preserve all existing records.

ALTER TABLE monitoring_collectors
  ADD COLUMN IF NOT EXISTS agent_version VARCHAR(40),
  ADD COLUMN IF NOT EXISTS service_started_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS last_error VARCHAR(500),
  ADD COLUMN IF NOT EXISTS consecutive_failures INTEGER NOT NULL DEFAULT 0;

ALTER TABLE monitoring_devices
  ADD COLUMN IF NOT EXISTS identity_key VARCHAR(220),
  ADD COLUMN IF NOT EXISTS device_uuid VARCHAR(160);

UPDATE monitoring_devices
SET identity_key = CASE
  WHEN mac_address IS NOT NULL AND length(regexp_replace(mac_address, '[^0-9A-Fa-f]', '', 'g')) = 12
    THEN 'mac:' || upper(regexp_replace(mac_address, '[^0-9A-Fa-f]', '', 'g'))
  ELSE 'legacy:' || id::text
END
WHERE identity_key IS NULL;

DELETE FROM monitoring_devices older
USING monitoring_devices newer
WHERE older.id <> newer.id
  AND older.identity_key = newer.identity_key
  AND (older.received_at < newer.received_at OR (older.received_at = newer.received_at AND older.id < newer.id));

CREATE UNIQUE INDEX IF NOT EXISTS monitoring_devices_identity_unique_idx
  ON monitoring_devices(identity_key) WHERE identity_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS monitoring_devices_uuid_idx ON monitoring_devices(device_uuid);

ALTER TABLE tickets
  ADD COLUMN IF NOT EXISTS sla_response_target_minutes INTEGER,
  ADD COLUMN IF NOT EXISTS sla_resolution_target_minutes INTEGER,
  ADD COLUMN IF NOT EXISTS sla_response_due_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS sla_resolution_due_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS sla_first_response_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS sla_resolved_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS sla_response_warning_level INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS sla_resolution_warning_level INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS sla_response_breached_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS sla_resolution_breached_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS sla_reopened_count INTEGER NOT NULL DEFAULT 0;

UPDATE tickets t
SET sla_response_target_minutes = COALESCE(t.sla_response_target_minutes, p.response_time, 60),
    sla_resolution_target_minutes = COALESCE(t.sla_resolution_target_minutes, p.resolution_time, 480)
FROM sla_policies p
WHERE p.priority = t.priority
  AND (t.sla_response_target_minutes IS NULL OR t.sla_resolution_target_minutes IS NULL);

UPDATE tickets
SET sla_response_target_minutes = COALESCE(sla_response_target_minutes, 60),
    sla_resolution_target_minutes = COALESCE(sla_resolution_target_minutes, 480),
    sla_response_due_at = COALESCE(sla_response_due_at, created_at + COALESCE(sla_response_target_minutes, 60) * INTERVAL '1 minute'),
    sla_resolution_due_at = COALESCE(sla_resolution_due_at, created_at + COALESCE(sla_resolution_target_minutes, 480) * INTERVAL '1 minute');

UPDATE tickets t
SET sla_first_response_at = response.first_response_at
FROM (
  SELECT ticket_id, MIN(timestamp) AS first_response_at
  FROM ticket_history
  WHERE updated_by !~ '^user-[0-9]+$'
  GROUP BY ticket_id
) response
WHERE t.id = response.ticket_id AND t.sla_first_response_at IS NULL;

UPDATE tickets t
SET sla_resolved_at = terminal.resolved_at
FROM (
  SELECT ticket_id, MIN(timestamp) AS resolved_at
  FROM ticket_history
  WHERE status IN ('RESOLVED', 'CLOSED')
  GROUP BY ticket_id
) terminal
WHERE t.id = terminal.ticket_id AND t.sla_resolved_at IS NULL;

UPDATE tickets
SET sla_response_breached_at = COALESCE(sla_first_response_at, CURRENT_TIMESTAMP)
WHERE sla_response_breached_at IS NULL
  AND COALESCE(sla_first_response_at, CURRENT_TIMESTAMP) > sla_response_due_at;

UPDATE tickets
SET sla_resolution_breached_at = COALESCE(sla_resolved_at, CURRENT_TIMESTAMP)
WHERE sla_resolution_breached_at IS NULL
  AND COALESCE(sla_resolved_at, CURRENT_TIMESTAMP) > sla_resolution_due_at
  AND (sla_resolved_at IS NOT NULL OR status NOT IN ('RESOLVED', 'CLOSED'));

CREATE INDEX IF NOT EXISTS tickets_sla_response_due_idx ON tickets(sla_response_due_at);
CREATE INDEX IF NOT EXISTS tickets_sla_resolution_due_idx ON tickets(sla_resolution_due_at);

CREATE TABLE IF NOT EXISTS sla_risk_predictions (
  id BIGSERIAL PRIMARY KEY,
  ticket_id INTEGER NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  score INTEGER NOT NULL,
  level VARCHAR(20) NOT NULL,
  reasons TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  UNIQUE(ticket_id, level)
);
CREATE INDEX IF NOT EXISTS sla_risk_predictions_ticket_idx ON sla_risk_predictions(ticket_id);
CREATE INDEX IF NOT EXISTS sla_risk_predictions_created_idx ON sla_risk_predictions(created_at);

CREATE TABLE IF NOT EXISTS monitoring_device_addresses (
  id BIGSERIAL PRIMARY KEY,
  device_id INTEGER NOT NULL REFERENCES monitoring_devices(id) ON DELETE CASCADE,
  collector_id UUID REFERENCES monitoring_collectors(id) ON DELETE SET NULL,
  ip_address VARCHAR(45) NOT NULL,
  mac_address VARCHAR(32),
  first_seen_at TIMESTAMP NOT NULL,
  last_seen_at TIMESTAMP NOT NULL,
  observation_count BIGINT NOT NULL DEFAULT 1,
  UNIQUE(device_id, ip_address)
);
CREATE INDEX IF NOT EXISTS monitoring_device_addresses_device_idx ON monitoring_device_addresses(device_id);
CREATE INDEX IF NOT EXISTS monitoring_device_addresses_collector_idx ON monitoring_device_addresses(collector_id);
CREATE INDEX IF NOT EXISTS monitoring_device_addresses_last_seen_idx ON monitoring_device_addresses(last_seen_at);

INSERT INTO monitoring_device_addresses (
  device_id, collector_id, ip_address, mac_address, first_seen_at, last_seen_at, observation_count
)
SELECT id, collector_id, ip_address, mac_address, received_at, last_seen_at, 1
FROM monitoring_devices
ON CONFLICT (device_id, ip_address) DO NOTHING;
