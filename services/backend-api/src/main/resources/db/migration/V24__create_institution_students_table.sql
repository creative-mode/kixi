-- Students are users holding the STUDENT role; a student can belong to several institutions.
CREATE TABLE IF NOT EXISTS institution_students (
    id             BIGSERIAL PRIMARY KEY,
    institution_id BIGINT NOT NULL,
    user_id        BIGINT NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at     TIMESTAMP WITH TIME ZONE DEFAULT NULL,

    CONSTRAINT uq_institution_student UNIQUE (institution_id, user_id),
    CONSTRAINT fk_institution_students_institution FOREIGN KEY (institution_id) REFERENCES institutions(id),
    CONSTRAINT fk_institution_students_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_institution_students_institution ON institution_students(institution_id);
CREATE INDEX idx_institution_students_user ON institution_students(user_id);
