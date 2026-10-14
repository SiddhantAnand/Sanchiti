-- Categories and banks a user adds on top of the built-in ones.
CREATE TABLE user_categories (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  direction TEXT NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_user_categories_name ON user_categories (user_id, direction, lower(name));

CREATE TABLE user_banks (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_user_banks_name ON user_banks (user_id, lower(name));

-- Split: shared expense groups. Kept apart from transactions; members may be linked to a login so they can see the
-- group (and nothing else of the owner's).
CREATE TABLE split_groups (
  id BIGSERIAL PRIMARY KEY,
  owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE split_members (
  id BIGSERIAL PRIMARY KEY,
  group_id BIGINT NOT NULL REFERENCES split_groups(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_split_members_user ON split_members (group_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_split_members_linked ON split_members (user_id) WHERE user_id IS NOT NULL;

CREATE TABLE split_expenses (
  id BIGSERIAL PRIMARY KEY,
  group_id BIGINT NOT NULL REFERENCES split_groups(id) ON DELETE CASCADE,
  description TEXT NOT NULL,
  amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
  date DATE NOT NULL,
  paid_by BIGINT NOT NULL REFERENCES split_members(id) ON DELETE CASCADE,
  -- Only ever one of the creator's own transactions; other members never see its details.
  transaction_id UUID REFERENCES transactions(id) ON DELETE SET NULL,
  created_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_split_expenses_group ON split_expenses (group_id, date);

CREATE TABLE split_shares (
  expense_id BIGINT NOT NULL REFERENCES split_expenses(id) ON DELETE CASCADE,
  member_id BIGINT NOT NULL REFERENCES split_members(id) ON DELETE CASCADE,
  amount_minor BIGINT NOT NULL CHECK (amount_minor >= 0),
  PRIMARY KEY (expense_id, member_id)
);

CREATE TABLE split_settlements (
  id BIGSERIAL PRIMARY KEY,
  group_id BIGINT NOT NULL REFERENCES split_groups(id) ON DELETE CASCADE,
  from_member BIGINT NOT NULL REFERENCES split_members(id) ON DELETE CASCADE,
  to_member BIGINT NOT NULL REFERENCES split_members(id) ON DELETE CASCADE,
  amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
  date DATE NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
  created_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_at TIMESTAMPTZ
);
CREATE INDEX idx_split_settlements_group ON split_settlements (group_id, status);
