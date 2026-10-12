-- Issue #119 (BE-23): opt-in sharing of a finished simulation's resolution.
--
-- A dedicated table keeps consent apart from the grade: sharing is explicit
-- (consent_at), anonymous by default, and revocable through soft-delete, so
-- revoking never touches simulations or simulation_answers.
CREATE TABLE IF NOT EXISTS solution_shares (
    id              BIGSERIAL PRIMARY KEY,
    simulation_id   BIGINT NOT NULL,
    account_id      BIGINT NOT NULL,
    statement_id    BIGINT NOT NULL,
    institution_id  BIGINT,
    anonymous       BOOLEAN NOT NULL DEFAULT TRUE,
    display_name    TEXT,
    consent_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at      TIMESTAMP WITH TIME ZONE,

    CONSTRAINT fk_solution_shares_simulation FOREIGN KEY (simulation_id)
        REFERENCES simulations(id) ON DELETE CASCADE,
    CONSTRAINT fk_solution_shares_account FOREIGN KEY (account_id)
        REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_solution_shares_statement FOREIGN KEY (statement_id)
        REFERENCES statements(id) ON DELETE CASCADE,
    CONSTRAINT fk_solution_shares_institution FOREIGN KEY (institution_id)
        REFERENCES institutions(id) ON DELETE SET NULL
);

-- One active share per simulation: sharing twice refreshes consent instead of
-- duplicating the row.
CREATE UNIQUE INDEX IF NOT EXISTS uq_solution_shares_active_simulation
    ON solution_shares(simulation_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_solution_shares_statement
    ON solution_shares(statement_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_solution_shares_statement_institution
    ON solution_shares(statement_id, institution_id) WHERE deleted_at IS NULL;

-- Support indexes for the community aggregates (finished simulations of a
-- statement and their answers per question).
CREATE INDEX IF NOT EXISTS idx_simulations_statement_finished
    ON simulations(statement_id) WHERE status = 'FINISHED' AND deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_simulation_answers_simulation_question
    ON simulation_answers(simulation_id, question_id) WHERE deleted_at IS NULL;
