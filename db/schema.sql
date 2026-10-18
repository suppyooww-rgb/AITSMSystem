-- Connect to the target application database before running this file.
CREATE TABLE IF NOT EXISTS users (
  id SERIAL PRIMARY KEY,
  full_name VARCHAR(150) NOT NULL,
  email VARCHAR(200) UNIQUE NOT NULL,
  company VARCHAR(150) NOT NULL,
  department VARCHAR(120) NOT NULL,
  profile_photo_url VARCHAR(500),
  password_hash VARCHAR(255) NOT NULL,
  role VARCHAR(20) NOT NULL DEFAULT 'end-user',
  email_verified BOOLEAN NOT NULL DEFAULT FALSE,
  verification_code VARCHAR(12),
  verification_expires_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Safe to run for existing deployments as well.
ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_photo_url VARCHAR(500);

CREATE TABLE IF NOT EXISTS profile_photo_requests (
  id SERIAL PRIMARY KEY,
  user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  photo_url VARCHAR(500) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  submitted_at TIMESTAMP NOT NULL DEFAULT NOW(),
  reviewed_at TIMESTAMP,
  reviewed_by INT REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS profile_photo_requests_user_status_idx ON profile_photo_requests(user_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS profile_photo_requests_one_pending_per_user ON profile_photo_requests(user_id) WHERE status = 'PENDING';

CREATE TABLE IF NOT EXISTS colleague_requests (
  id SERIAL PRIMARY KEY,
  sender_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipient_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  responded_at TIMESTAMP,
  CHECK (sender_id <> recipient_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS colleague_requests_pair_idx ON colleague_requests (LEAST(sender_id, recipient_id), GREATEST(sender_id, recipient_id));

CREATE TABLE IF NOT EXISTS direct_messages (
  id SERIAL PRIMARY KEY,
  sender_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipient_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  body VARCHAR(2000) NOT NULL,
  sent_at TIMESTAMP NOT NULL DEFAULT NOW(),
  read_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS direct_messages_pair_idx ON direct_messages(sender_id, recipient_id, sent_at);

CREATE TABLE IF NOT EXISTS eula_acceptance (
  id SERIAL PRIMARY KEY,
  user_id INT REFERENCES users(id),
  eula_version VARCHAR(20) NOT NULL,
  accepted_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
  id SERIAL PRIMARY KEY,
  device_name VARCHAR(150) NOT NULL,
  ip_address VARCHAR(45) NOT NULL,
  department VARCHAR(120) NOT NULL,
  assigned_user VARCHAR(150) NOT NULL,
  cpu_usage INT NOT NULL DEFAULT 0,
  memory_usage INT NOT NULL DEFAULT 0,
  status VARCHAR(50) NOT NULL,
  last_seen TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS tickets (
  id SERIAL PRIMARY KEY,
  user_id INT REFERENCES users(id),
  title VARCHAR(200) NOT NULL,
  description TEXT NOT NULL,
  priority VARCHAR(20) NOT NULL,
  category VARCHAR(80) NOT NULL,
  status VARCHAR(30) NOT NULL,
  assigned_to_user_id INT REFERENCES users(id) ON DELETE SET NULL,
  assigned_to VARCHAR(150),
  reviewed_resolution TEXT,
  resolution_updated_by VARCHAR(150),
  resolution_updated_at TIMESTAMP,
  sla_response_target_minutes INT,
  sla_resolution_target_minutes INT,
  sla_response_due_at TIMESTAMP,
  sla_resolution_due_at TIMESTAMP,
  sla_first_response_at TIMESTAMP,
  sla_resolved_at TIMESTAMP,
  sla_response_warning_level INT NOT NULL DEFAULT 0,
  sla_resolution_warning_level INT NOT NULL DEFAULT 0,
  sla_response_breached_at TIMESTAMP,
  sla_resolution_breached_at TIMESTAMP,
  sla_reopened_count INT NOT NULL DEFAULT 0,
  device_id INT REFERENCES devices(id),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS ticket_history (
    id SERIAL PRIMARY KEY,
    ticket_id INT REFERENCES tickets(id),
    status VARCHAR(30) NOT NULL,
    previous_status VARCHAR(30),
    work_note TEXT,
    user_message TEXT,
    resolution_category VARCHAR(80),
    notify_requester BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by VARCHAR(150) NOT NULL,
  timestamp TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS notifications (
  id SERIAL PRIMARY KEY,
  user_id INT REFERENCES users(id),
  title VARCHAR(180) NOT NULL DEFAULT 'Notification',
  message TEXT NOT NULL,
  type VARCHAR(40) NOT NULL,
  related_ticket_id INT REFERENCES tickets(id),
  related_user_id INT REFERENCES users(id) ON DELETE SET NULL,
  event_key VARCHAR(180) UNIQUE,
  is_read BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL,
  read_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sla_policies (
  id SERIAL PRIMARY KEY,
  priority VARCHAR(20) NOT NULL,
  response_time INT NOT NULL,
  resolution_time INT NOT NULL
);

CREATE TABLE IF NOT EXISTS sla_risk_predictions (
  id BIGSERIAL PRIMARY KEY,
  ticket_id INT NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  score INT NOT NULL,
  level VARCHAR(20) NOT NULL,
  reasons TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  UNIQUE(ticket_id, level)
);

CREATE TABLE IF NOT EXISTS monitoring_collectors (
  id UUID PRIMARY KEY,
  site_id VARCHAR(100) NOT NULL,
  name VARCHAR(150) NOT NULL,
  token_hash VARCHAR(64) NOT NULL UNIQUE,
  enrolled_at TIMESTAMP NOT NULL,
  last_seen_at TIMESTAMP,
  revoked_at TIMESTAMP,
  archived_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS monitoring_devices (
  id SERIAL PRIMARY KEY,
  collector_id UUID NOT NULL REFERENCES monitoring_collectors(id) ON DELETE CASCADE,
  device_key VARCHAR(200) NOT NULL,
  hostname VARCHAR(200) NOT NULL,
  ip_address VARCHAR(45) NOT NULL,
  mac_address VARCHAR(32),
  os_details VARCHAR(300),
  reachable BOOLEAN NOT NULL,
  telemetry_available BOOLEAN NOT NULL,
  cpu_usage_percent NUMERIC(5,2),
  memory_usage_percent NUMERIC(5,2),
  storage_total_bytes BIGINT,
  storage_used_bytes BIGINT,
  measurement_at TIMESTAMP,
  received_at TIMESTAMP NOT NULL,
  last_seen_at TIMESTAMP NOT NULL,
  UNIQUE(collector_id, device_key)
);

CREATE TABLE IF NOT EXISTS monitoring_device_addresses (
  id BIGSERIAL PRIMARY KEY,
  device_id INT NOT NULL REFERENCES monitoring_devices(id) ON DELETE CASCADE,
  collector_id UUID REFERENCES monitoring_collectors(id) ON DELETE SET NULL,
  ip_address VARCHAR(45) NOT NULL,
  mac_address VARCHAR(32),
  first_seen_at TIMESTAMP NOT NULL,
  last_seen_at TIMESTAMP NOT NULL,
  observation_count BIGINT NOT NULL DEFAULT 1,
  UNIQUE(device_id, ip_address)
);
CREATE INDEX IF NOT EXISTS monitoring_device_addresses_last_seen_idx ON monitoring_device_addresses(last_seen_at);

CREATE TABLE IF NOT EXISTS monitoring_discovery_requests (
  id UUID PRIMARY KEY,
  collector_id UUID NOT NULL REFERENCES monitoring_collectors(id) ON DELETE CASCADE,
  status VARCHAR(20) NOT NULL,
  requested_at TIMESTAMP NOT NULL,
  completed_at TIMESTAMP,
  error_message VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS notifications_related_user_idx ON notifications(related_user_id);
CREATE UNIQUE INDEX IF NOT EXISTS notifications_event_key_uidx ON notifications(event_key) WHERE event_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS system_settings (
  id SERIAL PRIMARY KEY,
  key VARCHAR(120) NOT NULL UNIQUE,
  value TEXT NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS knowledge_articles (
  id SERIAL PRIMARY KEY,
  title VARCHAR(200) NOT NULL,
  content TEXT NOT NULL,
  category VARCHAR(100) NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_logs (
  id SERIAL PRIMARY KEY,
  user_id INT,
  action VARCHAR(160) NOT NULL,
  entity VARCHAR(80) NOT NULL,
  timestamp TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_conversation_messages (
  id SERIAL PRIMARY KEY,
  user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  session_id VARCHAR(128) NOT NULL,
  role VARCHAR(32) NOT NULL,
  content TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS ai_conversation_messages_owner_session_idx
  ON ai_conversation_messages(user_id, session_id, created_at);

CREATE TABLE IF NOT EXISTS inventory_assets (
  id SERIAL PRIMARY KEY,
  asset_tag VARCHAR(120) UNIQUE,
  hostname VARCHAR(200),
  full_device_name VARCHAR(255),
  manufacturer VARCHAR(150),
  model VARCHAR(180),
  processor_name VARCHAR(255),
  processor_speed VARCHAR(100),
  installed_ram VARCHAR(100),
  usable_ram VARCHAR(100),
  ram_speed VARCHAR(100),
  gpu_name VARCHAR(255),
  gpu_memory VARCHAR(100),
  storage_total VARCHAR(100),
  storage_used VARCHAR(100),
  storage_free VARCHAR(100),
  storage_breakdown_json TEXT,
  system_type VARCHAR(180),
  os_name VARCHAR(180),
  os_edition VARCHAR(180),
  os_version VARCHAR(120),
  os_build VARCHAR(120),
  installed_on VARCHAR(120),
  domain_or_workgroup VARCHAR(160),
  device_uuid VARCHAR(160),
  product_id VARCHAR(160),
  pen_touch_support VARCHAR(120),
  ip_address VARCHAR(45),
  mac_address VARCHAR(80),
  connection_source VARCHAR(80),
  status VARCHAR(50) NOT NULL DEFAULT 'offline',
  assigned_department VARCHAR(150),
  assigned_user VARCHAR(180),
  notes TEXT,
  last_seen_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS inventory_asset_snapshots (
  id SERIAL PRIMARY KEY,
  asset_id INT NOT NULL REFERENCES inventory_assets(id) ON DELETE CASCADE,
  snapshot_source VARCHAR(80) NOT NULL,
  payload_json TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
