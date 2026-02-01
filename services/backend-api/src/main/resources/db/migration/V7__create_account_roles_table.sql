CREATE TABLE IF NOT EXISTS account_roles (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP,
    CONSTRAINT uq_account_role UNIQUE (account_id, role_id),
    CONSTRAINT fk_account_roles_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT fk_account_roles_role FOREIGN KEY (role_id) REFERENCES roles(id)
);

CREATE INDEX idx_account_roles_account_id ON account_roles(account_id);
CREATE INDEX idx_account_roles_role_id ON account_roles(role_id);
CREATE INDEX idx_account_roles_deleted_at ON account_roles(deleted_at);
