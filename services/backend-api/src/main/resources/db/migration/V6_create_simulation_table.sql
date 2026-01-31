CREATE TABLE simulation(
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    statement_id BIGINT NOT NULL,
    school_year_id BIGINT,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    time_spent_seconds INTEGER,
    final_score DOUBLE PRECISION,
    status VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX idx_simulation_account ON simulation(account_id);
CREATE INDEX idx_simulation_statement ON simulation(statement_id);
CREATE INDEX idx_simulation_school_year ON simulation(school_year_id);
CREATE UNIQUE INDEX idx_unique_active_simulation ON simulation(account_id, statement_id)
WHERE deleted_at IS NULL AND finished_at IS NULL;
