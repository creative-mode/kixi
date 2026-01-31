-- Script de criação da tabela 'terms'
CREATE TABLE IF NOT EXISTS terms (
    id BIGSERIAL PRIMARY KEY,
    number INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at  TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    );

-- Índices para otimizar as buscas por Soft Delete
CREATE INDEX idx_terms_deleted_at ON terms(deleted_at);

-- Comentários para documentação da tabela
COMMENT ON TABLE terms IS 'Tabela que armazena os períodos/trimestres letivos do sistema.';