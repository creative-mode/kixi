CREATE TABLE school_years (
    id          BIGSERIAL PRIMARY KEY,
    start_year  INTEGER NOT NULL,
    end_year    INTEGER NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at  TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    
    CONSTRAINT uk_school_years_start_end UNIQUE (start_year, end_year),
    CONSTRAINT chk_start_before_end CHECK (start_year < end_year)
);

CREATE INDEX idx_school_years_active    ON school_years (deleted_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_school_years_start_end ON school_years (start_year, end_year);