ALTER TABLE tickets
  ADD COLUMN IF NOT EXISTS assigned_to_user_id INT REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS tickets_assigned_to_user_id_idx
  ON tickets(assigned_to_user_id);

-- Preserve any trustworthy legacy assignments whose display value uniquely matches a user.
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
