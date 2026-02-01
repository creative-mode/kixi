CREATE TABLE classes (
   code VARCHAR(50) PRIMARY KEY ,
   grade VARCHAR(20) NOT NULL,
   course_id BIGINT NOT NULL,
   school_year_id BIGINT NOT NULL,
   created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
   updated_at TIMESTAMP  WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
   deleted_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,


    CONSTRAINT fk_course FOREIGN KEY (course_id) REFERENCES courses(id),
    CONSTRAINT fk_school_year FOREIGN KEY (school_year_id) REFERENCES school_years(id)
);