CREATE TABLE simulation (
    id                  BIGSERIAL PRIMARY KEY,
    account_id          BIGINT NOT NULL,
    statement_id        BIGINT NOT NULL,
    school_year_id      BIGINT,
    started_at          TIMESTAMP WITH TIME ZONE,
    finished_at         TIMESTAMP WITH TIME ZONE,
    time_spent_seconds  INTEGER,
    final_score         DECIMAL(5,2),
    status              VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE,
    deleted_at          TIMESTAMP WITH TIME ZONE,

    CONSTRAINT fk_simulation_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT fk_simulation_statement FOREIGN KEY (statement_id) REFERENCES statement(id),
    CONSTRAINT fk_simulation_school_year FOREIGN KEY (school_year_id) REFERENCES school_years(id),
    CONSTRAINT chk_simulation_status CHECK (status IN ('IN_PROGRESS', 'FINISHED', 'CANCELLED'))
);

CREATE INDEX idx_simulation_active ON simulation (deleted_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_simulation_account ON simulation (account_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_simulation_statement ON simulation (statement_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_simulation_status ON simulation (status) WHERE deleted_at IS NULL;
