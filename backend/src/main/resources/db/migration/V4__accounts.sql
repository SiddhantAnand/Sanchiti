-- Accounts: everyone signs in with a login ID and sees only their own data.
CREATE TABLE users (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  login          TEXT NOT NULL UNIQUE,
  display_name   TEXT NOT NULL,
  -- BCrypt; empty until the owner's password is set from APP_PASSWORD on the next start.
  password_hash  TEXT NOT NULL,
  -- SHA-256 of the Bearer key the user's phone sends SMS with.
  phone_key_hash TEXT UNIQUE,
  admin          BOOLEAN NOT NULL DEFAULT false,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Single-use sign-up codes created by an admin; only a SHA-256 of the code is stored.
CREATE TABLE invites (
  code_hash  TEXT PRIMARY KEY,
  created_by BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Data saved before accounts existed belongs to the first (owner) account.
INSERT INTO users (login, display_name, password_hash, admin)
SELECT 'admin', 'Owner', '', true
WHERE EXISTS (SELECT 1 FROM transactions) OR EXISTS (SELECT 1 FROM review)
   OR EXISTS (SELECT 1 FROM analyses) OR EXISTS (SELECT 1 FROM reason_categories);

ALTER TABLE transactions ADD COLUMN user_id BIGINT REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE review ADD COLUMN user_id BIGINT REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE analyses ADD COLUMN user_id BIGINT REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE reason_categories ADD COLUMN user_id BIGINT REFERENCES users(id) ON DELETE CASCADE;

UPDATE transactions SET user_id = (SELECT min(id) FROM users);
UPDATE review SET user_id = (SELECT min(id) FROM users);
UPDATE analyses SET user_id = (SELECT min(id) FROM users);
UPDATE reason_categories SET user_id = (SELECT min(id) FROM users);

ALTER TABLE transactions ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE review ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE analyses ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE reason_categories ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE reason_categories DROP CONSTRAINT reason_categories_pkey, ADD PRIMARY KEY (user_id, reason_key);
ALTER TABLE review DROP CONSTRAINT review_payload_hash_key, ADD UNIQUE (user_id, payload_hash);

DROP INDEX idx_tx_date;
DROP INDEX idx_tx_dedupe;
DROP INDEX idx_tx_reason_key;
CREATE INDEX idx_tx_user_date ON transactions(user_id, date);
CREATE INDEX idx_tx_user_dedupe ON transactions(user_id, dedupe_key);
CREATE INDEX idx_tx_user_reason_key ON transactions(user_id, reason_key);
CREATE INDEX idx_analyses_user ON analyses(user_id, id);

-- Old single-user sessions cannot be mapped to an account; everyone signs in again.
DELETE FROM sessions;
ALTER TABLE sessions DROP COLUMN user_name;
ALTER TABLE sessions ADD COLUMN user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE;
