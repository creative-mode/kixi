-- 1. Tabela de Posts
CREATE TABLE IF NOT EXISTS posts (
                                     id BIGSERIAL PRIMARY KEY,
                                     author_account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    school_year_id BIGINT NULL,
    course_id BIGINT NULL,
    class_id BIGINT NULL,
    type VARCHAR(20) NOT NULL,
    title VARCHAR(150) NULL,
    content TEXT NOT NULL,
    is_auto_generated BOOLEAN NOT NULL DEFAULT FALSE,
    is_hidden BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITHOUT TIME ZONE NULL
    );

-- Índices para otimizar as buscas no Feed por escopo do aluno
CREATE INDEX IF NOT EXISTS idx_posts_scoping
    ON posts(school_year_id, course_id, class_id, is_hidden, created_at DESC);

-- 2. Tabela de Reações
CREATE TABLE IF NOT EXISTS post_reactions (
                                              id BIGSERIAL PRIMARY KEY,
                                              post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    type VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_post_account_reaction UNIQUE (post_id, account_id, type)
    );

-- 3. Tabela de Comentários
CREATE TABLE IF NOT EXISTS post_comments (
                                             id BIGSERIAL PRIMARY KEY,
                                             post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    author_account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    content VARCHAR(500) NOT NULL,
    is_hidden BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITHOUT TIME ZONE NULL
    );

CREATE INDEX IF NOT EXISTS idx_post_comments_post_id
    ON post_comments(post_id, is_hidden, created_at ASC);

-- 4. Tabela de Moderação (Denúncias)
CREATE TABLE IF NOT EXISTS post_reports (
                                            id BIGSERIAL PRIMARY KEY,
                                            post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    reporter_account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    reason VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_post_reporter UNIQUE (post_id, reporter_account_id)
    );