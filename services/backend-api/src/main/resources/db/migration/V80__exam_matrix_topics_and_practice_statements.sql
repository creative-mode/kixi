-- V80: matriz da prova e treino por temas (kixi#729).
--
-- Até aqui as questões não tinham tema. Esta migração introduz:
--   1. a taxonomia de temas de uma disciplina (e, opcionalmente, de uma classe) e a sua ligação às questões;
--   2. a matriz de especificação de uma prova (temas, n.º de itens, cotação numa escala, nível cognitivo);
--   3. o enunciado «virtual» de um treino montado pelo aluno: um enunciado privado, com cópias das questões escolhidas,
--      que nunca aparece no catálogo, nas listas, nas estatísticas nem no ranking (statements.practice_owner_account_id).

CREATE TABLE subject_topics (
    id          BIGSERIAL PRIMARY KEY,
    subject_id  BIGINT       NOT NULL REFERENCES subjects (id),
    grade       INTEGER,
    name        VARCHAR(200) NOT NULL,
    slug        VARCHAR(200) NOT NULL,
    keywords    TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at  TIMESTAMP WITH TIME ZONE
);

-- Um tema por disciplina, classe (0 = todas) e nome normalizado, enquanto estiver ativo.
CREATE UNIQUE INDEX uq_subject_topics_slug
    ON subject_topics (subject_id, COALESCE(grade, 0), slug) WHERE deleted_at IS NULL;
CREATE INDEX idx_subject_topics_subject ON subject_topics (subject_id) WHERE deleted_at IS NULL;

CREATE TABLE question_topics (
    question_id BIGINT      NOT NULL REFERENCES questions (id) ON DELETE CASCADE,
    topic_id    BIGINT      NOT NULL REFERENCES subject_topics (id),
    source      VARCHAR(10) NOT NULL DEFAULT 'MANUAL',
    PRIMARY KEY (question_id, topic_id),
    CONSTRAINT chk_question_topics_source CHECK (source IN ('MANUAL', 'AUTO'))
);
CREATE INDEX idx_question_topics_topic ON question_topics (topic_id);

CREATE TABLE exam_matrices (
    id           BIGSERIAL PRIMARY KEY,
    exam_room_id BIGINT         NOT NULL UNIQUE REFERENCES exam_rooms (id) ON DELETE CASCADE,
    title        VARCHAR(200),
    total_points NUMERIC(6, 2)  NOT NULL DEFAULT 20,
    created_by   BIGINT         NOT NULL REFERENCES accounts (id),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_exam_matrices_total CHECK (total_points > 0)
);

CREATE TABLE exam_matrix_items (
    id              BIGSERIAL PRIMARY KEY,
    matrix_id       BIGINT        NOT NULL REFERENCES exam_matrices (id) ON DELETE CASCADE,
    topic_id        BIGINT        NOT NULL REFERENCES subject_topics (id),
    question_count  INTEGER       NOT NULL,
    points          NUMERIC(6, 2),
    cognitive_level VARCHAR(20),
    question_types  TEXT,
    position        INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT chk_exam_matrix_items_count CHECK (question_count > 0),
    CONSTRAINT chk_exam_matrix_items_level
        CHECK (cognitive_level IS NULL OR cognitive_level IN ('KNOWLEDGE', 'COMPREHENSION', 'APPLICATION', 'ANALYSIS')),
    CONSTRAINT uq_exam_matrix_items_topic UNIQUE (matrix_id, topic_id)
);

-- Treino montado: o título da simulação («Treino · ...»).
ALTER TABLE simulations ADD COLUMN title VARCHAR(200);

-- O enunciado virtual de um treino: privado do aluno dono. NULL em todos os enunciados «a sério».
ALTER TABLE statements ADD COLUMN practice_owner_account_id BIGINT REFERENCES accounts (id);
CREATE INDEX idx_statements_practice_owner ON statements (practice_owner_account_id)
    WHERE practice_owner_account_id IS NOT NULL;

-- A questão de origem de uma cópia de treino: liga as respostas do aluno às questões originais.
ALTER TABLE questions ADD COLUMN source_question_id BIGINT REFERENCES questions (id) ON DELETE SET NULL;
CREATE INDEX idx_questions_source ON questions (source_question_id) WHERE source_question_id IS NOT NULL;
