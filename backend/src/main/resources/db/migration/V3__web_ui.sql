-- Free-text details added by hand in the web UI.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS note TEXT;

-- Web UI sign-in sessions; only a SHA-256 of the cookie token is stored.
CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,
  user_name  TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
