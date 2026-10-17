ALTER TABLE ticket_history
  ADD COLUMN IF NOT EXISTS previous_status VARCHAR(30),
  ADD COLUMN IF NOT EXISTS work_note TEXT,
  ADD COLUMN IF NOT EXISTS user_message TEXT,
  ADD COLUMN IF NOT EXISTS resolution_category VARCHAR(80),
  ADD COLUMN IF NOT EXISTS notify_requester BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE ticket_history
SET work_note = 'Legacy status change recorded before transition notes were enabled.'
WHERE work_note IS NULL;

