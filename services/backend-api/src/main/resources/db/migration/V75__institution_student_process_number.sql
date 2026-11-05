-- V75: número de processo do aluno na escola (kixi#512).
--
-- É do aluno NA ESCOLA (muda de escola para escola), por isso vive na ligação aluno–instituição e não na conta.
-- Opcional, até 30 caracteres (o limite do contrato v1 do motor: alunos[].processo). Único por instituição quando
-- preenchido; a ligação apagada (deleted_at) liberta o número.
ALTER TABLE institution_students
    ADD COLUMN IF NOT EXISTS process_number VARCHAR(30);

ALTER TABLE institution_students
    ADD CONSTRAINT chk_institution_students_process_number
    CHECK (process_number IS NULL OR (length(btrim(process_number)) > 0 AND length(process_number) <= 30));

CREATE UNIQUE INDEX IF NOT EXISTS uq_institution_students_process_number
    ON institution_students (institution_id, process_number)
    WHERE process_number IS NOT NULL AND deleted_at IS NULL;
