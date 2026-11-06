-- V79: treino montado à medida (kixi#729).
--
-- Uma simulação normal usa todas as questões do enunciado (coluna a NULL). Um treino montado guarda, em CSV,
-- os ids das questões escolhidas: a correção, a nota máxima e o resultado só contam essas.
ALTER TABLE simulations ADD COLUMN selected_question_ids TEXT;
