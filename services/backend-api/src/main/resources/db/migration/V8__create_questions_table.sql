CREATE TABLE questions (
    id BIGSERIAL PRIMARY KEY,
    statement_id BIGINT NOT NULL,
    number INTEGER NOT NULL,
    text TEXT NOT NULL,
    question_type VARCHAR(50) NOT NULL,
    max_score DECIMAL(10, 2) NOT NULL,
    order_index INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP

    -- Ensures a unique sequence of question numbers within a specific statement
    -- CONSTRAINT uk_questions_statement_number UNIQUE (statement_id, number)
);

-- Optimization for foreign key lookups
-- CREATE INDEX idx_questions_statement_id ON questions(statement_id);

-- Partial index for Soft Delete performance (optimizes retrieval of active records)
CREATE INDEX idx_questions_deleted_at ON questions(deleted_at) WHERE deleted_at IS NULL;