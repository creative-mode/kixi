-- Issue #119 (BE-23), review follow-up: harden the consent table.
--
-- A named share must carry the name it shows, and the name has a bounded
-- length even if a writer bypasses the service validation. The listing reads
-- newest-first per statement, so it gets a matching index.
ALTER TABLE solution_shares
    ADD CONSTRAINT chk_solution_shares_named_display_name
    CHECK (anonymous OR display_name IS NOT NULL);

ALTER TABLE solution_shares
    ADD CONSTRAINT chk_solution_shares_display_name_length
    CHECK (display_name IS NULL OR char_length(display_name) <= 120);

CREATE INDEX IF NOT EXISTS idx_solution_shares_statement_created
    ON solution_shares(statement_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_solution_shares_statement_inst_created
    ON solution_shares(statement_id, institution_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;
