-- The profile and catalog both ask for the newest live enrollment of an account.
-- The shared index avoids materializing and sorting the account's full history.

DROP INDEX IF EXISTS idx_enrollments_catalog_account;

CREATE INDEX IF NOT EXISTS idx_enrollments_account_recent
    ON enrollments (account_id, id DESC)
    WHERE deleted_at IS NULL;
