-- Selo de verificação de escola (issue #332).
--
-- Uma escola só mostra o selo depois de um ADMIN a verificar. O pedido guarda o que a escola
-- declarou (nome oficial, número de registo, contacto institucional, documento) e a decisão fica
-- registada com quem decidiu e quando. A escola nunca se verifica a si própria.

ALTER TABLE institutions
    ADD COLUMN verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN verified_at TIMESTAMP WITHOUT TIME ZONE NULL,
    ADD COLUMN verified_by_account_id BIGINT NULL REFERENCES accounts(id) ON DELETE SET NULL;

CREATE TABLE institution_verification_requests (
    id BIGSERIAL PRIMARY KEY,
    institution_id BIGINT NOT NULL REFERENCES institutions(id) ON DELETE CASCADE,
    official_name VARCHAR(255) NOT NULL,
    registration_number VARCHAR(60) NOT NULL,
    contact_email VARCHAR(150) NOT NULL,
    document_url VARCHAR(500) NULL,
    notes VARCHAR(1000) NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'pending'
        CONSTRAINT ck_institution_verification_requests_status
        CHECK (status IN ('pending', 'approved', 'rejected')),
    review_note VARCHAR(500) NULL,
    requested_by_account_id BIGINT NULL REFERENCES accounts(id) ON DELETE SET NULL,
    reviewed_by_account_id BIGINT NULL REFERENCES accounts(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMP WITHOUT TIME ZONE NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_institution_verification_requests_status_created
    ON institution_verification_requests (status, created_at DESC);

-- No máximo um pedido por decidir por escola.
CREATE UNIQUE INDEX uk_institution_verification_requests_pending
    ON institution_verification_requests (institution_id)
    WHERE status = 'pending';
