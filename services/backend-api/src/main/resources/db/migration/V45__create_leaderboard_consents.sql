-- Consent to be ranked across institutions: the gate behind
-- `GET /api/v1/leaderboard?scope=global` (issue #189, ADR-0013).
--
-- Opt-in, and the absence of a row means "has not consented". A ranking that crosses the
-- school boundary publishes a student's performance to people they were never seated with,
-- so nobody is published until they have said yes. One row per account, not per school year:
-- a student who consented once consented for good, and re-asking every September is worse
-- for consent than it is for privacy.
--
-- `revoked_at` is kept alongside the flag so that taking consent back is auditable rather
-- than indistinguishable from never having had it.
CREATE TABLE IF NOT EXISTS leaderboard_consents (
    id                 BIGSERIAL PRIMARY KEY,
    account_id         BIGINT NOT NULL,
    cross_institution  BOOLEAN NOT NULL DEFAULT FALSE,
    consented_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    revoked_at         TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    created_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,

    -- One row per account: the unique index is what makes the "upsert the consent" a
    -- question with exactly one answer, instead of two rows disagreeing about the same
    -- student and leaving the ranking to pick whichever came back first.
    CONSTRAINT uq_leaderboard_consents_account UNIQUE (account_id),
    CONSTRAINT fk_leaderboard_consents_account FOREIGN KEY (account_id) REFERENCES accounts(id)
);

-- The ranking resolves membership through an EXISTS keyed on the account, once per
-- candidate student. Without this the whole cross-institution set is a sequential scan.
CREATE INDEX IF NOT EXISTS idx_leaderboard_consents_cross_institution
    ON leaderboard_consents (account_id)
    WHERE cross_institution = TRUE AND revoked_at IS NULL;
