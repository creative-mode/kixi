-- Códigos de matrícula (issue #255).
--
-- Até aqui, quem queria matricular-se a si próprio escolhia livremente classId e
-- schoolYearId no corpo do pedido: qualquer conta com o papel STUDENT entrava em
-- qualquer turma de qualquer escola, bastava conhecer o id. Esta tabela guarda os
-- códigos que uma escola emite (ADMIN) e que o aluno troca por uma matrícula na
-- turma certa — classId e schoolYearId passam a vir do código, não do cliente.
--
-- institution_id e school_year_id não se repetem aqui: já vivem em classes
-- (class_id -> institution_id, school_year_id) e chegam-se lá por essa turma.

CREATE TABLE enrollment_codes (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(32) NOT NULL,
    class_id BIGINT NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
    max_uses INT NOT NULL DEFAULT 1,
    uses INT NOT NULL DEFAULT 0,
    expires_at TIMESTAMP WITHOUT TIME ZONE NULL,
    created_by_account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE SET NULL,
    revoked_at TIMESTAMP WITHOUT TIME ZONE NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_enrollment_codes_code ON enrollment_codes (code);
CREATE INDEX idx_enrollment_codes_class_id ON enrollment_codes (class_id);

-- Qual código, se algum, deu origem a cada matrícula - para auditoria e para que
-- revogar um código não precise de adivinhar quem entrou por ele.
ALTER TABLE enrollments
    ADD COLUMN enrollment_code_id BIGINT NULL REFERENCES enrollment_codes(id) ON DELETE SET NULL;

CREATE INDEX idx_enrollments_enrollment_code_id ON enrollments (enrollment_code_id);
