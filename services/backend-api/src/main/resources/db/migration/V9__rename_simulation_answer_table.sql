-- Rename simulation_answer table to simulation_answers (plural) to follow naming convention
ALTER TABLE simulation_answer RENAME TO simulation_answers;

-- Rename indexes to match new table name
ALTER INDEX idx_simulation_answer_simulation_id RENAME TO idx_simulation_answers_simulation_id;
ALTER INDEX idx_simulation_answer_question_id RENAME TO idx_simulation_answers_question_id;
ALTER INDEX idx_simulation_answer_deleted_at RENAME TO idx_simulation_answers_deleted_at;
