CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    photo VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP,
    CONSTRAINT fk_users_account_id FOREIGN KEY (account_id) REFERENCES accounts(id)
);

CREATE INDEX idx_users_account_id ON users(account_id);
CREATE INDEX idx_users_deleted_at ON users(deleted_at);
