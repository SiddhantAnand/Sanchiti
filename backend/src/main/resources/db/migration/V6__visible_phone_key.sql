-- The phone key itself, so its owner can see it again in Help. A phone key can only post SMS, never read data.
ALTER TABLE users ADD COLUMN phone_key TEXT;
