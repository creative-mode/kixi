-- Plano do trimestre (P1, P2 e as avaliações de MAC) e coordenador por instituição (kixi#523, kixi#513).
INSERT INTO roles (name, description) VALUES
    ('COORDINATOR', 'Coordenador de uma instituição: acompanha os planos e as pautas e pode dispensar uma componente')
ON CONFLICT (name) DO NOTHING;

-- O coordenador é por instituição. O vigilante não tem tabela: é sempre um professor.
CREATE TABLE institution_coordinators (
    institution_id BIGINT NOT NULL REFERENCES institutions(id),
    account_id     BIGINT NOT NULL REFERENCES accounts(id),
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (institution_id, account_id)
);
CREATE INDEX idx_institution_coordinators_account ON institution_coordinators(account_id);

CREATE TABLE term_plans (
    id                 BIGSERIAL PRIMARY KEY,
    class_id           BIGINT NOT NULL REFERENCES classes(id),
    subject_id         BIGINT NOT NULL REFERENCES subjects(id),
    term_id            BIGINT NOT NULL REFERENCES terms(id),
    created_by         BIGINT NOT NULL REFERENCES accounts(id),
    approved_by        BIGINT REFERENCES accounts(id),
    approved_at        TIMESTAMP,
    pauta_opened_at    TIMESTAMP,
    pauta_ref          VARCHAR(120),
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_term_plans UNIQUE (class_id, subject_id, term_id)
);

CREATE TABLE term_assessments (
    id             BIGSERIAL PRIMARY KEY,
    plan_id        BIGINT NOT NULL REFERENCES term_plans(id) ON DELETE CASCADE,
    kind           VARCHAR(5) NOT NULL,
    seq            INTEGER NOT NULL DEFAULT 1,
    title          VARCHAR(200) NOT NULL,
    planned_date   DATE NOT NULL,
    statement_id   BIGINT REFERENCES statements(id),
    room_id        BIGINT REFERENCES exam_rooms(id),
    manual_done_at TIMESTAMP,
    dispensed_by   BIGINT REFERENCES accounts(id),
    dispensed_at   TIMESTAMP,
    dispensed_reason VARCHAR(500),
    CONSTRAINT ck_term_assessment_kind CHECK (kind IN ('P1', 'P2', 'MAC')),
    CONSTRAINT ck_term_assessment_seq CHECK (seq >= 1),
    CONSTRAINT uq_term_assessment UNIQUE (plan_id, kind, seq)
);
CREATE INDEX idx_term_assessments_room ON term_assessments(room_id);
