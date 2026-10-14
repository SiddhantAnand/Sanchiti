-- A member who didn't add an expense can only ask for it to be deleted; the person who added it decides.
ALTER TABLE split_expenses
  ADD COLUMN delete_requested_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
  ADD COLUMN delete_requested_at TIMESTAMPTZ;
