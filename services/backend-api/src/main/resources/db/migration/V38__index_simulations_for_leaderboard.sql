-- Indexes for the relative ranking (issue #117).
--
-- The ranking query filters on school year and status, and asks per simulation whether
-- any answer is still waiting for a teacher. Both were column scans over the whole
-- table, once per cohort on the first request of each 45-second window.

-- The cohort is one school year, and only finished simulations carry a score.
CREATE INDEX IF NOT EXISTS idx_simulations_year_status
    ON simulations (school_year_id, status)
    WHERE deleted_at IS NULL;

-- The NOT EXISTS runs per simulation; answering it from an index turns it into a
-- single lookup instead of a scan of that simulation's answers.
CREATE INDEX IF NOT EXISTS idx_simulation_answers_simulation_review
    ON simulation_answers (simulation_id, review_status)
    WHERE deleted_at IS NULL;