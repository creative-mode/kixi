CREATE TABLE questions (
    id BIGSERIAL PRIMARY KEY,
    statement_id BIGINT NOT NULL,
    number INTEGER NOT NULL,
    text TEXT NOT NULL,
    question_type VARCHAR(50) NOT NULL DEFAULT 'unknown',
    max_score DECIMAL(10, 2),
    order_index INTEGER,
    ocr_confidence DECIMAL(5, 4),
    page_index INTEGER NOT NULL DEFAULT 0,
    needs_review BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT uk_questions_statement_number UNIQUE (statement_id, number),
    CONSTRAINT fk_questions_statement
        FOREIGN KEY (statement_id) REFERENCES statements(id) ON DELETE CASCADE
);

CREATE INDEX idx_questions_statement_id ON questions(statement_id);
CREATE INDEX idx_questions_deleted_at ON questions(deleted_at) WHERE deleted_at IS NULL;
