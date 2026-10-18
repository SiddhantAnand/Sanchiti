-- Foreign-currency spends are stored in INR; these keep what was actually charged and the rate used.
ALTER TABLE transactions
  ADD COLUMN fx_amount_minor BIGINT,
  ADD COLUMN fx_currency TEXT,
  ADD COLUMN fx_rate NUMERIC(18, 6),
  ADD COLUMN fx_date DATE;
