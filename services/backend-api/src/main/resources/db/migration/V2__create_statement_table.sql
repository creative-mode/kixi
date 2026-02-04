CREATE TABLE statement (
    id                  BIGSERIAL PRIMARY KEY,
    exam_type           VARCHAR(50) NOT NULL,
    duration_minutes    INTEGER,
    variant             VARCHAR(50),
    title               VARCHAR(255) NOT NULL,
    instructions        TEXT,
    total_max_score     INTEGER,
    school_year_id      BIGINT,
    term_id             BIGINT,
    subject_id          BIGINT,
    class_id            BIGINT,
    course_id           BIGINT,
    create_by           BIGINT,
    visible             BOOLEAN DEFAULT false,
    create_at           TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_at           TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    delete_at           TIMESTAMP WITH TIME ZONE DEFAULT NULL
);

CREATE INDEX idx_statement_active ON statement (delete_at) WHERE delete_at IS NULL;
CREATE INDEX idx_statement_school_year ON statement (school_year_id) WHERE delete_at IS NULL;
CREATE INDEX idx_statement_subject ON statement (subject_id) WHERE delete_at IS NULL;
CREATE INDEX idx_statement_class ON statement (class_id) WHERE delete_at IS NULL;
CREATE INDEX idx_statement_term ON statement (term_id) WHERE delete_at IS NULL;
