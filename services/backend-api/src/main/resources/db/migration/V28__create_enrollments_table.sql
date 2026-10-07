-- V28: student enrollments (account in a class for a school year).
--
-- One active enrollment per account and school year: enforced by the
-- partial unique index below (soft-deleted rows don't conflict, so a
-- cancelled enrollment can be replaced by restoring or re-creating it).

CREATE TABLE IF NOT EXISTS enrollments (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    class_id BIGINT NOT NULL,
    school_year_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,

    CONSTRAINT fk_enrollments_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT fk_enrollments_class FOREIGN KEY (class_id) REFERENCES classes(id),
    CONSTRAINT fk_enrollments_school_year FOREIGN KEY (school_year_id) REFERENCES school_years(id),
    CONSTRAINT chk_enrollments_status CHECK (status IN ('ACTIVE', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_enrollments_account ON enrollments(account_id);
CREATE INDEX IF NOT EXISTS idx_enrollments_class ON enrollments(class_id);
CREATE INDEX IF NOT EXISTS idx_enrollments_school_year ON enrollments(school_year_id);

-- A student cannot hold two active enrollments in the same school year.
CREATE UNIQUE INDEX IF NOT EXISTS uq_enrollments_account_school_year_active
    ON enrollments(account_id, school_year_id)
    WHERE deleted_at IS NULL;
