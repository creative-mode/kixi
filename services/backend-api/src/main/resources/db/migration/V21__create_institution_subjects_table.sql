CREATE TABLE IF NOT EXISTS institution_subjects (
    id             BIGSERIAL PRIMARY KEY,
    institution_id BIGINT NOT NULL,
    subject_id     BIGINT NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at     TIMESTAMP WITH TIME ZONE DEFAULT NULL,

    CONSTRAINT uq_institution_subject UNIQUE (institution_id, subject_id),
    CONSTRAINT fk_institution_subjects_institution FOREIGN KEY (institution_id) REFERENCES institutions(id),
    CONSTRAINT fk_institution_subjects_subject FOREIGN KEY (subject_id) REFERENCES subjects(id)
);

CREATE INDEX idx_institution_subjects_institution ON institution_subjects(institution_id);
CREATE INDEX idx_institution_subjects_subject ON institution_subjects(subject_id);
