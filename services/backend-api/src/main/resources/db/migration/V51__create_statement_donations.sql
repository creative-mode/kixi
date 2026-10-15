-- Doação pública de enunciados (issue #252).
--
-- Qualquer pessoa pode submeter fotos ou PDF de provas em kixidonate.creativemode.ao. Um
-- administrador revê cada doação e, se a aceitar, converte-a num enunciado pelo construtor.
--
-- O IP de quem doa nunca é guardado: só o seu SHA-256 com um pepper de configuração
-- (`client_hash`), que serve para travar abuso e para um administrador reconhecer que duas
-- doações vieram do mesmo sítio, sem poder voltar ao endereço. O e-mail é opcional e só serve a
-- um administrador para agradecer ou pedir um esclarecimento.

CREATE TABLE statement_donations (
    id BIGSERIAL PRIMARY KEY,
    -- Código curto e legível que o doador guarda ("KD-7F3K-92QX"); o id sequencial não se mostra.
    reference VARCHAR(16) NOT NULL UNIQUE,
    donor_name VARCHAR(100) NULL,
    donor_email VARCHAR(150) NULL,
    institution_name VARCHAR(150) NOT NULL,
    subject_name VARCHAR(100) NOT NULL,
    class_grade VARCHAR(30) NULL,
    school_year VARCHAR(20) NULL,
    exam_type VARCHAR(40) NOT NULL,
    notes VARCHAR(1000) NULL,
    -- O consentimento é um facto datado e versionado: quem doou aceitou ESTE texto, nesse momento.
    consent_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    consent_version VARCHAR(20) NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'pending'
        CONSTRAINT ck_statement_donations_status
        CHECK (status IN ('pending', 'approved', 'rejected', 'converted')),
    review_note VARCHAR(500) NULL,
    reviewed_by_account_id BIGINT NULL REFERENCES accounts(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMP WITHOUT TIME ZONE NULL,
    -- O enunciado criado a partir desta doação. Apagar o enunciado não apaga a doação.
    statement_id BIGINT NULL REFERENCES statements(id) ON DELETE SET NULL,
    client_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_statement_donations_status_created
    ON statement_donations (status, created_at DESC);

CREATE INDEX idx_statement_donations_client_hash
    ON statement_donations (client_hash);

CREATE TABLE statement_donation_files (
    id BIGSERIAL PRIMARY KEY,
    donation_id BIGINT NOT NULL REFERENCES statement_donations(id) ON DELETE CASCADE,
    -- A chave no storage é aleatória: o nome original nunca entra no caminho.
    storage_key VARCHAR(200) NOT NULL UNIQUE,
    original_name VARCHAR(200) NOT NULL,
    content_type VARCHAR(50) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 CHAR(64) NOT NULL,
    page_count INTEGER NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Dois ficheiros iguais na mesma doação são um só.
    CONSTRAINT uk_statement_donation_files_sha UNIQUE (donation_id, sha256)
);

CREATE INDEX idx_statement_donation_files_donation
    ON statement_donation_files (donation_id);

-- A mesma janela deslizante dos outros limitadores (registo, login), em tabela própria: duas
-- proteções diferentes numa tabela só ficam separadas enquanto ninguém se enganar no prefixo.
CREATE TABLE donation_rate_limits (
    rate_key VARCHAR(64) PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts > 0),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_donation_rate_limits_updated_at
    ON donation_rate_limits (updated_at);
