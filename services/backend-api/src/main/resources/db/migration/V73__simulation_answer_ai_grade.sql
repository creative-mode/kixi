-- A correção das dissertativas das provas de um plano é feita pela IA no fluxo do motor SchoolOps (kixi#542): o Kixi guarda a nota,
-- a observação e a confiança que o motor lhe devolve, e mostra-as ao professor (só leitura) e ao aluno.
ALTER TABLE simulation_answers
    ADD COLUMN ai_feedback    TEXT,
    ADD COLUMN ai_level       VARCHAR(10),
    ADD COLUMN ai_confidence  DOUBLE PRECISION,
    ADD COLUMN ai_graded_at   TIMESTAMP;

-- Acrescenta o estado AI_GRADED sem tocar nas respostas existentes.
ALTER TABLE simulation_answers DROP CONSTRAINT chk_simulation_answer_review_status;
ALTER TABLE simulation_answers
    ADD CONSTRAINT chk_simulation_answer_review_status
    CHECK (review_status IN ('PENDING_REVIEW', 'GRADED', 'AUTO_GRADED', 'AI_GRADED'));
ALTER TABLE simulation_answers
    ADD CONSTRAINT chk_simulation_answer_ai_level
    CHECK (ai_level IS NULL OR ai_level IN ('TOTAL', 'PARCIAL', 'MINIMA', 'NULA'));
ALTER TABLE simulation_answers
    ADD CONSTRAINT chk_simulation_answer_ai_confidence
    CHECK (ai_confidence IS NULL OR (ai_confidence >= 0 AND ai_confidence <= 1));
