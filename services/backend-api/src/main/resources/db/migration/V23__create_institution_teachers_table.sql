CREATE TABLE IF NOT EXISTS institution_teachers (
    id             BIGSERIAL PRIMARY KEY,
    institution_id BIGINT NOT NULL,
    teacher_id     BIGINT NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at     TIMESTAMP WITH TIME ZONE DEFAULT NULL,

    CONSTRAINT uq_institution_teacher UNIQUE (institution_id, teacher_id),
    CONSTRAINT fk_institution_teachers_institution FOREIGN KEY (institution_id) REFERENCES institutions(id),
    CONSTRAINT fk_institution_teachers_teacher FOREIGN KEY (teacher_id) REFERENCES teachers(id)
);

CREATE INDEX idx_institution_teachers_institution ON institution_teachers(institution_id);
CREATE INDEX idx_institution_teachers_teacher ON institution_teachers(teacher_id);
