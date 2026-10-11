-- Server-wide settings an admin changes in the web UI (e.g. the Gemini API key); they override the environment.
CREATE TABLE settings (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
