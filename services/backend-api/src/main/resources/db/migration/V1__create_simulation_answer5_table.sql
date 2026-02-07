CREATE TABLE simulation_answers(
    id BIGSERIAL PRIMARY KEY,
    simulation_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    selected_option_id BIGINT,
    answer_text TEXT,
    score_obtained REAL NOT NULL DEFAULT 0,
    is_correct BOOLEAN,
    answered_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,

    CONSTRAINT fk_simulation FOREIGN KEY(simulation_id)
        REFERENCES simulation(id)
        ON DELETE CASCADE,

    CONSTRAINT uq_simulation_question UNIQUE(simulation_id, question_id)
);

CREATE INDEX idx_simulation_answers_simulation_id ON simulation_answers(simulation_id);
CREATE INDEX idx_simulation_answers_question_id ON simulation_answers(question_id);
CREATE INDEX idx_simulation_answers_deleted_at ON simulation_answers(deleted_at);
