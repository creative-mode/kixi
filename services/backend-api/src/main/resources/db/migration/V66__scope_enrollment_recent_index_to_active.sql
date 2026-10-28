DROP INDEX IF EXISTS idx_enrollments_account_recent;

CREATE INDEX IF NOT EXISTS idx_enrollments_account_recent
    ON enrollments (account_id, id DESC)
    WHERE deleted_at IS NULL AND status = 'ACTIVE';
