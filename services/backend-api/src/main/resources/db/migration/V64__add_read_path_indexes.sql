-- Read-path indexes for the student catalog and full statement view.
--
-- Each index matches the predicates and ordering of a hot student-facing query.
-- The partial predicates keep cancelled/deleted rows out of the index and avoid
-- adding write cost for data that these reads never return.

CREATE INDEX IF NOT EXISTS idx_enrollments_catalog_account
    ON enrollments (account_id, id DESC)
    WHERE deleted_at IS NULL AND status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_questions_statement_live_order
    ON questions (statement_id, COALESCE(order_index, number), id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_question_options_question_live_order
    ON question_options (question_id, order_index, id)
    WHERE deleted_at IS NULL;
