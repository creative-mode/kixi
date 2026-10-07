-- A teacher assigned to teach a subject to a class during a school year. This is what
-- scopes which statements a teacher may create, edit and publish: institution_teachers
-- says who belongs to a school, this says who is responsible for which class and subject.
CREATE TABLE IF NOT EXISTS teaching_assignments (
    id             BIGSERIAL PRIMARY KEY,
    teacher_id     BIGINT NOT NULL,
    class_id       BIGINT NOT NULL,
    subject_id     BIGINT NOT NULL,
    school_year_id BIGINT NOT NULL,
    tutor_style    VARCHAR(100),
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at     TIMESTAMP WITH TIME ZONE DEFAULT NULL,

    CONSTRAINT uq_teaching_assignment UNIQUE (teacher_id, class_id, subject_id, school_year_id),
    CONSTRAINT fk_teaching_assignments_teacher FOREIGN KEY (teacher_id) REFERENCES teachers(id),
    CONSTRAINT fk_teaching_assignments_class FOREIGN KEY (class_id) REFERENCES classes(id),
    CONSTRAINT fk_teaching_assignments_subject FOREIGN KEY (subject_id) REFERENCES subjects(id),
    CONSTRAINT fk_teaching_assignments_school_year FOREIGN KEY (school_year_id) REFERENCES school_years(id)
);

CREATE INDEX idx_teaching_assignments_active ON teaching_assignments(deleted_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_teaching_assignments_teacher ON teaching_assignments(teacher_id);
CREATE INDEX idx_teaching_assignments_class ON teaching_assignments(class_id);
CREATE INDEX idx_teaching_assignments_subject ON teaching_assignments(subject_id);
