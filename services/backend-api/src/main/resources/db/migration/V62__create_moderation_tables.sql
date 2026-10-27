CREATE TABLE moderation_blocks (
    id BIGSERIAL PRIMARY KEY,
    account_low BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    account_high BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_moderation_block_pair UNIQUE (account_low, account_high),
    CONSTRAINT chk_moderation_block_order CHECK (account_low < account_high)
);

CREATE TABLE moderation_reports (
    id BIGSERIAL PRIMARY KEY,
    target_type VARCHAR(20) NOT NULL,
    target_account_id BIGINT REFERENCES accounts(id) ON DELETE CASCADE,
    target_institution_id BIGINT REFERENCES institutions(id) ON DELETE CASCADE,
    reporter_account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    reason VARCHAR(32) NOT NULL,
    note VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_moderation_report_target_type CHECK (target_type IN ('ACCOUNT', 'INSTITUTION')),
    CONSTRAINT chk_moderation_report_one_target CHECK (
        (target_type = 'ACCOUNT' AND target_account_id IS NOT NULL AND target_institution_id IS NULL)
        OR (target_type = 'INSTITUTION' AND target_account_id IS NULL AND target_institution_id IS NOT NULL)
    ),
    CONSTRAINT chk_moderation_report_reason CHECK (
        reason IN ('IMPERSONATION', 'FAKE_SCHOOL', 'OFFENSIVE_NAME', 'OFFENSIVE_PHOTO',
                   'MISLEADING_CONTENT', 'BLOCK', 'OTHER')
    )
);

CREATE INDEX idx_moderation_reports_target
    ON moderation_reports (target_type, target_account_id, target_institution_id, created_at DESC);
