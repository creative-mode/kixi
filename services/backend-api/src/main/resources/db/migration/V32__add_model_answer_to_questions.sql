-- The answer a free-response question expects, so there is something for automatic
-- correction to compare against. A multiple choice answer lives on
-- question_options.is_correct instead; a question can have either, and the
-- approval gate only insists on the latter for question_type = 'multiple_choice'.
--
-- questions.max_score already carries the score per question, so it is not added
-- here.
ALTER TABLE questions ADD COLUMN IF NOT EXISTS model_answer TEXT;