-- Money in as well as money out. Every existing row is a debit.
ALTER TABLE transactions
  ADD COLUMN direction TEXT NOT NULL DEFAULT 'DEBIT' CHECK (direction IN ('DEBIT', 'CREDIT'));

CREATE INDEX idx_tx_user_direction_date ON transactions (user_id, direction, date);
