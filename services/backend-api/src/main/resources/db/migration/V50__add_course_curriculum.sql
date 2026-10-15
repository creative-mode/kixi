-- Estrutura académica (issue #253): escola -> cursos -> turmas; curso -> disciplinas (currículo);
-- turma -> disciplinas (as do curso, para a sua classe); disciplina numa turma -> professor.
--
-- 1) O código de um curso passa a ser único POR ESCOLA, entre os ativos. Até aqui era único no
--    sistema inteiro (V5), por isso "o mesmo curso em duas escolas" obrigava a inventar códigos.
--    O apagado deixa de reservar o código (uma escola pode reutilizá-lo); restaurar um curso cujo
--    código foi reutilizado é um 409 que o serviço explica.
ALTER TABLE courses DROP CONSTRAINT IF EXISTS uc_courses_code;
ALTER TABLE courses DROP CONSTRAINT IF EXISTS courses_code_key;
DROP INDEX IF EXISTS idx_courses_code;
CREATE UNIQUE INDEX IF NOT EXISTS uk_courses_institution_code
    ON courses (institution_id, code) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_courses_code ON courses (code);

-- 2) Currículo: que disciplinas tem um curso. `grade` é a classe a que se aplica (NULL = todas as
--    classes do curso). Uma disciplina pode estar em vários cursos, de várias escolas.
CREATE TABLE IF NOT EXISTS course_subjects (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL REFERENCES courses (id),
    subject_id BIGINT NOT NULL REFERENCES subjects (id),
    grade INTEGER NULL CHECK (grade > 0),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITHOUT TIME ZONE NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_course_subjects_active
    ON course_subjects (course_id, subject_id, COALESCE(grade, 0)) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_course_subjects_subject ON course_subjects (subject_id) WHERE deleted_at IS NULL;

-- 3) `institution_subjects` passa a ser o CATÁLOGO da escola e o currículo está contido nele: o
--    serviço garante a linha do catálogo ao ligar uma disciplina a um curso.
--
-- Backfill: para o comportamento de hoje não mudar, cada curso ativo recebe, para todas as classes,
-- as disciplinas ativas que a sua escola já tinha no catálogo.
INSERT INTO course_subjects (course_id, subject_id, grade)
SELECT c.id, isub.subject_id, NULL
FROM courses c
JOIN institution_subjects isub ON isub.institution_id = c.institution_id AND isub.deleted_at IS NULL
JOIN subjects s ON s.id = isub.subject_id AND s.deleted_at IS NULL
WHERE c.deleted_at IS NULL
ON CONFLICT DO NOTHING;
