-- Script de criação da tabela 'terms'
CREATE TABLE IF NOT EXISTS terms (
    id SERIAL PRIMARY KEY,
    number INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
    );

-- Índices para otimizar as buscas por Soft Delete
CREATE INDEX idx_terms_deleted_at ON terms(deleted_at);

-- Comentários para documentação da tabela
COMMENT ON TABLE terms IS 'Tabela que armazena os períodos/trimestres letivos do sistema.';