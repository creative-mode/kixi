-- The legacy docker/postgres/init.sql created the complete schema without
-- Flyway history. After baselining that schema at V16, this migration brings
-- its nullable columns, status values, and account_roles key in line with the
-- runtime contract used by the V1-V17 migration chain.

ALTER TABLE accounts ADD COLUMN IF NOT EXISTS active BOOLEAN;
UPDATE accounts SET active = TRUE WHERE active IS NULL;
ALTER TABLE accounts ALTER COLUMN active SET DEFAULT TRUE;
ALTER TABLE accounts ALTER COLUMN active SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_accounts_active ON accounts(active);

UPDATE users SET first_name = '' WHERE first_name IS NULL;
UPDATE users SET last_name = '' WHERE last_name IS NULL;
ALTER TABLE users ALTER COLUMN first_name SET DEFAULT '';
ALTER TABLE users ALTER COLUMN first_name SET NOT NULL;
ALTER TABLE users ALTER COLUMN last_name SET DEFAULT '';
ALTER TABLE users ALTER COLUMN last_name SET NOT NULL;

-- V7 introduced a surrogate key required by the R2DBC AccountRole entity.
CREATE SEQUENCE IF NOT EXISTS account_roles_id_seq;
ALTER TABLE account_roles ADD COLUMN IF NOT EXISTS id BIGINT;
UPDATE account_roles
SET id = nextval('account_roles_id_seq')
WHERE id IS NULL;
ALTER SEQUENCE account_roles_id_seq OWNED BY account_roles.id;
ALTER TABLE account_roles ALTER COLUMN id SET DEFAULT nextval('account_roles_id_seq');
ALTER TABLE account_roles ALTER COLUMN id SET NOT NULL;
SELECT setval(
    'account_roles_id_seq',
    GREATEST(COALESCE((SELECT MAX(id) FROM account_roles), 0) + 1, 1),
    FALSE
);

ALTER TABLE account_roles DROP CONSTRAINT IF EXISTS account_roles_pkey;
ALTER TABLE account_roles ADD CONSTRAINT account_roles_pkey PRIMARY KEY (id);
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.account_roles'::regclass
          AND conname = 'uq_account_role'
    ) THEN
        ALTER TABLE account_roles
            ADD CONSTRAINT uq_account_role UNIQUE (account_id, role_id);
    END IF;
END;
$$;
CREATE INDEX IF NOT EXISTS idx_account_roles_account_id ON account_roles(account_id);
CREATE INDEX IF NOT EXISTS idx_account_roles_role_id ON account_roles(role_id);
CREATE INDEX IF NOT EXISTS idx_account_roles_deleted_at ON account_roles(deleted_at);

UPDATE statements SET visible = FALSE WHERE visible IS NULL;
UPDATE statements SET needs_review = FALSE WHERE needs_review IS NULL;
UPDATE statements SET source = 'manual' WHERE source IS NULL;
ALTER TABLE statements ALTER COLUMN visible SET DEFAULT FALSE;
ALTER TABLE statements ALTER COLUMN visible SET NOT NULL;
ALTER TABLE statements ALTER COLUMN needs_review SET DEFAULT FALSE;
ALTER TABLE statements ALTER COLUMN needs_review SET NOT NULL;
ALTER TABLE statements ALTER COLUMN source SET DEFAULT 'manual';
ALTER TABLE statements ALTER COLUMN source SET NOT NULL;

UPDATE questions SET page_index = 0 WHERE page_index IS NULL;
UPDATE questions SET needs_review = FALSE WHERE needs_review IS NULL;
ALTER TABLE questions ALTER COLUMN page_index SET DEFAULT 0;
ALTER TABLE questions ALTER COLUMN page_index SET NOT NULL;
ALTER TABLE questions ALTER COLUMN needs_review SET DEFAULT FALSE;
ALTER TABLE questions ALTER COLUMN needs_review SET NOT NULL;

UPDATE question_options SET is_correct = FALSE WHERE is_correct IS NULL;
UPDATE question_options SET order_index = 0 WHERE order_index IS NULL;
ALTER TABLE question_options ALTER COLUMN is_correct SET DEFAULT FALSE;
ALTER TABLE question_options ALTER COLUMN is_correct SET NOT NULL;
ALTER TABLE question_options ALTER COLUMN order_index SET DEFAULT 0;
ALTER TABLE question_options ALTER COLUMN order_index SET NOT NULL;

UPDATE simulations
SET status = CASE LOWER(TRIM(status))
    WHEN 'finished' THEN 'FINISHED'
    WHEN 'cancelled' THEN 'CANCELLED'
    ELSE 'IN_PROGRESS'
END
WHERE status IS NULL OR status <> UPPER(status) OR status NOT IN ('IN_PROGRESS', 'FINISHED', 'CANCELLED');
ALTER TABLE simulations ALTER COLUMN status SET DEFAULT 'IN_PROGRESS';
ALTER TABLE simulations ALTER COLUMN status SET NOT NULL;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.simulations'::regclass
          AND conname = 'chk_simulation_status'
    ) THEN
        ALTER TABLE simulations
            ADD CONSTRAINT chk_simulation_status
            CHECK (status IN ('IN_PROGRESS', 'FINISHED', 'CANCELLED'));
    END IF;
END;
$$;

UPDATE simulation_answers SET score_obtained = 0 WHERE score_obtained IS NULL;
ALTER TABLE simulation_answers ALTER COLUMN score_obtained SET DEFAULT 0;
ALTER TABLE simulation_answers ALTER COLUMN score_obtained SET NOT NULL;
