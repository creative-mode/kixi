CREATE TABLE statements (
    id                  BIGSERIAL PRIMARY KEY,
    exam_type           VARCHAR(100),
    duration_minutes    INTEGER,
    variant             VARCHAR(50),
    title               VARCHAR(500),
    instructions        TEXT,
    total_max_score     DECIMAL(10, 2),
    school_year_id      BIGINT,
    term_id             BIGINT,
    subject_id          BIGINT,
    class_id            BIGINT,
    course_id           BIGINT,
    created_by          BIGINT,
    visible             BOOLEAN NOT NULL DEFAULT FALSE,
    needs_review        BOOLEAN NOT NULL DEFAULT FALSE,
    ocr_confidence      DECIMAL(5, 4),
    ocr_request_id      VARCHAR(100),
    source              VARCHAR(50) NOT NULL DEFAULT 'manual',
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at          TIMESTAMP WITH TIME ZONE,

    CONSTRAINT fk_statements_school_year FOREIGN KEY (school_year_id) REFERENCES school_years(id),
    CONSTRAINT fk_statements_term FOREIGN KEY (term_id) REFERENCES terms(id),
    CONSTRAINT fk_statements_subject FOREIGN KEY (subject_id) REFERENCES subjects(id),
    CONSTRAINT fk_statements_class FOREIGN KEY (class_id) REFERENCES classes(id),
    CONSTRAINT fk_statements_course FOREIGN KEY (course_id) REFERENCES courses(id),
    CONSTRAINT fk_statements_created_by FOREIGN KEY (created_by) REFERENCES accounts(id)
);

CREATE INDEX idx_statements_active ON statements (deleted_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_statements_school_year ON statements (school_year_id);
CREATE INDEX idx_statements_subject ON statements (subject_id);
CREATE INDEX idx_statements_class ON statements (class_id);
CREATE INDEX idx_statements_term ON statements (term_id);
CREATE INDEX idx_statements_created_by ON statements (created_by);
CREATE INDEX idx_statements_visible ON statements (visible);
CREATE INDEX idx_statements_needs_review ON statements (needs_review);
CREATE INDEX idx_statements_source ON statements (source);
