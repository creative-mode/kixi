-- A course is offered by one school, and so is each of its classes. Both hold the
-- institution as an opaque id: `academic` must not depend on the `institutions` module
-- (see ArchitectureTest), so it never imports the institution types and never resolves
-- the name here. The name is resolved by `institutions`, which owns schools.
--
-- `courses.code` stays globally unique, which in practice means each school prefixes its
-- own codes. Sharing one code across schools would need UNIQUE (institution_id, code)
-- and is left for a later migration rather than changing uniqueness semantics here.

ALTER TABLE classes ADD COLUMN IF NOT EXISTS institution_id BIGINT;

ALTER TABLE courses ADD COLUMN IF NOT EXISTS institution_id BIGINT;

-- Nothing records which school the existing courses and classes belonged to, so the only
-- defensible backfill is the oldest institution. Fail loudly when there is academic data
-- but no institution to attribute it to, instead of guessing.
DO $$
DECLARE
    fallback BIGINT;
BEGIN
    SELECT MIN(id) INTO fallback FROM institutions;

    IF EXISTS (SELECT 1 FROM courses) AND fallback IS NULL THEN
        RAISE EXCEPTION
            'cannot attribute existing courses to a school: no institution exists';
    END IF;

    IF EXISTS (SELECT 1 FROM classes) AND fallback IS NULL THEN
        RAISE EXCEPTION
            'cannot attribute existing classes to a school: no institution exists';
    END IF;

    UPDATE courses SET institution_id = fallback WHERE institution_id IS NULL;
    UPDATE classes SET institution_id = fallback WHERE institution_id IS NULL;
END $$;

ALTER TABLE courses ALTER COLUMN institution_id SET NOT NULL;
ALTER TABLE classes ALTER COLUMN institution_id SET NOT NULL;

-- A class cannot sit in a different school from its course.
UPDATE classes c
SET institution_id = co.institution_id
FROM courses co
WHERE co.id = c.course_id
  AND co.institution_id IS DISTINCT FROM c.institution_id;

-- Target of the composite foreign key below, so it has to exist first.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uc_courses_id_institution'
    ) THEN
        ALTER TABLE courses ADD CONSTRAINT uc_courses_id_institution UNIQUE (id, institution_id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_courses_institution') THEN
        ALTER TABLE courses ADD CONSTRAINT fk_courses_institution
            FOREIGN KEY (institution_id) REFERENCES institutions(id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_classes_institution') THEN
        ALTER TABLE classes ADD CONSTRAINT fk_classes_institution
            FOREIGN KEY (institution_id) REFERENCES institutions(id);
    END IF;

    -- Enforces that a class sits in the same school as its course, in the database
    -- rather than only in the service layer.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_classes_course_institution') THEN
        ALTER TABLE classes ADD CONSTRAINT fk_classes_course_institution
            FOREIGN KEY (course_id, institution_id) REFERENCES courses(id, institution_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_courses_institution ON courses(institution_id);
CREATE INDEX IF NOT EXISTS idx_courses_institution_deleted
    ON courses(institution_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_classes_institution ON classes(institution_id);
CREATE INDEX IF NOT EXISTS idx_classes_institution_course
    ON classes(institution_id, course_id) WHERE deleted_at IS NULL;
