-- Read-path indexes for the student statement catalog.
--
-- These indexes match the predicates and ordering of the catalog's existing
-- question and option queries. They are kept with the catalog change so the
-- academic-read PR does not install indexes for a separate feature.

CREATE INDEX IF NOT EXISTS idx_questions_statement_live_order
    ON questions (statement_id, COALESCE(order_index, number), id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_question_options_question_live_order
    ON question_options (question_id, order_index, id)
    WHERE deleted_at IS NULL;
