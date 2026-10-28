-- Read-path index for the newest active enrollment used by the profile and catalog.
--
-- The partial predicate keeps cancelled/deleted rows out of the index and avoids
-- adding write cost for data that these reads never return.

CREATE INDEX IF NOT EXISTS idx_enrollments_account_recent
    ON enrollments (account_id, id DESC)
    WHERE deleted_at IS NULL AND status = 'ACTIVE';
