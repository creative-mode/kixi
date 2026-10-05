CREATE TABLE IF NOT EXISTS institutions (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(50) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    short_name  VARCHAR(50),
    logo        TEXT,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at  TIMESTAMP WITH TIME ZONE DEFAULT NULL,

    CONSTRAINT uk_institutions_code UNIQUE (code)
);

CREATE INDEX idx_institutions_active ON institutions(deleted_at) WHERE deleted_at IS NULL;
