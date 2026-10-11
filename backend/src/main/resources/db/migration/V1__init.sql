CREATE TABLE IF NOT EXISTS transactions (
  id              UUID PRIMARY KEY,
  date            DATE NOT NULL,
  amount_minor    BIGINT NOT NULL CHECK (amount_minor > 0),
  currency        TEXT NOT NULL DEFAULT 'INR',
  reason          TEXT NOT NULL,
  reason_key      TEXT NOT NULL,
  merchant        TEXT,
  payment_method  TEXT,
  upi_ref         TEXT,
  card_last4      TEXT,
  bank            TEXT,
  category        TEXT,
  category_source TEXT,
  dedupe_key      TEXT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_tx_date ON transactions(date);
CREATE INDEX IF NOT EXISTS idx_tx_dedupe ON transactions(dedupe_key);
CREATE INDEX IF NOT EXISTS idx_tx_reason_key ON transactions(reason_key);

CREATE TABLE IF NOT EXISTS reason_categories (
  reason_key TEXT PRIMARY KEY,
  category   TEXT NOT NULL,
  source     TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS analyses (
  id          INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  period_from DATE NOT NULL,
  period_to   DATE NOT NULL,
  model       TEXT NOT NULL,
  input_hash  TEXT NOT NULL,
  stats       JSONB NOT NULL,
  insights    JSONB NOT NULL,
  token_usage JSONB,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
