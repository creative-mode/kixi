-- A teacher is a person profile. It only gets access to the platform when an account is linked (account_id).
CREATE TABLE IF NOT EXISTS teachers (
    id              BIGSERIAL PRIMARY KEY,
    account_id      BIGINT,
    first_name      VARCHAR(100) NOT NULL,
    last_name       VARCHAR(100) NOT NULL,
    email           VARCHAR(255),
    photo           VARCHAR(500),
    specialty       VARCHAR(255),
    employee_number VARCHAR(50),
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at      TIMESTAMP WITH TIME ZONE DEFAULT NULL,

    CONSTRAINT uk_teachers_account UNIQUE (account_id),
    CONSTRAINT uk_teachers_employee_number UNIQUE (employee_number),
    CONSTRAINT fk_teachers_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE SET NULL
);

CREATE INDEX idx_teachers_active ON teachers(deleted_at) WHERE deleted_at IS NULL;
