-- Tutor modes (issue #115). A session keeps what the student opened it for: the
-- mode, the question in focus and, for "explicar questão", the answer they gave.
-- Existing sessions become GENERAL, which is the tutor of #114 unchanged.
ALTER TABLE chat_sessions
    ADD COLUMN mode VARCHAR(30) NOT NULL DEFAULT 'GENERAL',
    ADD COLUMN question_id BIGINT,
    ADD COLUMN selected_option_id BIGINT,
    ADD COLUMN student_answer TEXT;

ALTER TABLE chat_sessions
    ADD CONSTRAINT chk_chat_session_mode
        CHECK (mode IN ('GENERAL', 'EXPLAIN_QUESTION', 'SIMILAR_EXERCISES', 'CONCEPT')),
    ADD CONSTRAINT fk_chat_sessions_question
        FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_chat_sessions_option
        FOREIGN KEY (selected_option_id) REFERENCES question_options(id) ON DELETE SET NULL;
