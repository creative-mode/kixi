ALTER TABLE simulation_answers
    ALTER COLUMN score_obtained DROP NOT NULL,
    ADD COLUMN review_status VARCHAR(20);

UPDATE simulation_answers
SET review_status = CASE
    WHEN is_correct IS NULL THEN 'PENDING_REVIEW'
    ELSE 'AUTO_GRADED'
END;

ALTER TABLE simulation_answers
    ADD CONSTRAINT chk_simulation_answer_review_status
    CHECK (review_status IN ('PENDING_REVIEW', 'GRADED', 'AUTO_GRADED'));
