-- Registo escolar (kixi#501): o estado durável que o motor SchoolOps escreve e lê. Tudo idempotente por chave.

-- A nota de cada aluno numa prova (enunciado), já com as rectificações.
CREATE TABLE register_exam_grades (
    statement_id BIGINT NOT NULL REFERENCES statements(id),
    account_id   BIGINT NOT NULL REFERENCES accounts(id),
    student_number INTEGER,
    student_name VARCHAR(255),
    score        NUMERIC(5, 2),            -- 0..20; NULL = sem nota (faltou), nunca zero
    status       VARCHAR(30),              -- situação devolvida pelo motor
    payload      JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (statement_id, account_id),
    CONSTRAINT ck_register_exam_score CHECK (score IS NULL OR (score >= 0 AND score <= 20))
);

-- Uma pauta trimestral (turma, disciplina, trimestre): contexto, estado e estatísticas.
CREATE TABLE register_term_sheets (
    sheet_id   VARCHAR(80) PRIMARY KEY,
    context    JSONB NOT NULL DEFAULT '{}'::jsonb,
    statistics JSONB NOT NULL DEFAULT '{}'::jsonb,
    status     VARCHAR(20) NOT NULL DEFAULT 'REGISTADA',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_register_term_sheet_status CHECK (status IN ('REGISTADA', 'FECHADA', 'ANULADA'))
);

-- As linhas de uma pauta por componente (NPP, NPT, MAC, MT) e aluno.
CREATE TABLE register_term_entries (
    sheet_id   VARCHAR(80) NOT NULL,
    component  VARCHAR(20) NOT NULL,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    score      NUMERIC(5, 2),
    status     VARCHAR(30),
    payload    JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (sheet_id, component, account_id)
);
CREATE INDEX idx_register_term_entries_account ON register_term_entries(account_id);

-- Reclamações: pendente até o motor a dar por tratada.
CREATE TABLE register_appeals (
    appeal_id    VARCHAR(80) PRIMARY KEY,
    statement_id BIGINT NOT NULL REFERENCES statements(id),
    account_id   BIGINT NOT NULL REFERENCES accounts(id),
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDENTE',
    decision     TEXT,
    payload      JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at  TIMESTAMP,
    CONSTRAINT ck_register_appeal_status CHECK (status IN ('PENDENTE', 'TRATADA'))
);
CREATE INDEX idx_register_appeals_statement ON register_appeals(statement_id, status);
