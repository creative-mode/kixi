CREATE TABLE simulations (
    id                  BIGSERIAL PRIMARY KEY,
    account_id          BIGINT NOT NULL,
    statement_id        BIGINT NOT NULL,
    school_year_id      BIGINT,
    started_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at         TIMESTAMP WITH TIME ZONE,
    time_spent_seconds  INTEGER,
    final_score         DECIMAL(10, 2),
    status              VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at          TIMESTAMP WITH TIME ZONE,

    CONSTRAINT fk_simulation_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT fk_simulation_statement FOREIGN KEY (statement_id) REFERENCES statements(id),
    CONSTRAINT fk_simulation_school_year FOREIGN KEY (school_year_id) REFERENCES school_years(id),
    CONSTRAINT chk_simulation_status CHECK (status IN ('IN_PROGRESS', 'FINISHED', 'CANCELLED'))
);

CREATE INDEX idx_simulations_active ON simulations (deleted_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_simulations_account ON simulations (account_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_simulations_statement ON simulations (statement_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_simulations_status ON simulations (status) WHERE deleted_at IS NULL;
