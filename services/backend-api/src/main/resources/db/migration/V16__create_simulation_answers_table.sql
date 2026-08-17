CREATE TABLE simulation_answers (
    id BIGSERIAL PRIMARY KEY,
    simulation_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    selected_option_id BIGINT,
    answer_text TEXT,
    score_obtained DECIMAL(10, 2) NOT NULL DEFAULT 0,
    is_correct BOOLEAN,
    answered_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT fk_simulation_answers_simulation
        FOREIGN KEY (simulation_id) REFERENCES simulations(id) ON DELETE CASCADE,
    CONSTRAINT fk_simulation_answers_question
        FOREIGN KEY (question_id) REFERENCES questions(id),
    CONSTRAINT fk_simulation_answers_option
        FOREIGN KEY (selected_option_id) REFERENCES question_options(id),
    CONSTRAINT uq_simulation_answers_simulation_question
        UNIQUE (simulation_id, question_id)
);

CREATE INDEX idx_simulation_answers_simulation ON simulation_answers(simulation_id);
CREATE INDEX idx_simulation_answers_question ON simulation_answers(question_id);
CREATE INDEX idx_simulation_answers_deleted_at ON simulation_answers(deleted_at);
