ALTER TABLE simulation_answers
    ALTER COLUMN score_obtained DROP NOT NULL,
    ADD COLUMN review_status VARCHAR(20);

-- Legacy answers stay unclassified. Before server-side submission, is_correct
-- was not populated, so a backfill would incorrectly queue finished papers.
-- New submissions assign a review status as each answer is corrected.

ALTER TABLE simulation_answers
    ADD CONSTRAINT chk_simulation_answer_review_status
    CHECK (review_status IN ('PENDING_REVIEW', 'GRADED', 'AUTO_GRADED'));
