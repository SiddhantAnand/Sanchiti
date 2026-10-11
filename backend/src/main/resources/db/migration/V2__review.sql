-- Incoming records that could not be stored as transactions (e.g. no amount), kept for manual review.
CREATE TABLE IF NOT EXISTS review (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  payload      JSONB NOT NULL,
  issue        TEXT NOT NULL,
  payload_hash TEXT NOT NULL UNIQUE,
  received_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
