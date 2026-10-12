-- Feed social da turma/escola (issue #120).
--
-- institution_id é NOT NULL desde o início: sem ele o isolamento por escola
-- não se consegue exprimir em SQL, e school_year_id e course_id por si só não
-- bastam porque não são próprios de uma escola.

CREATE TABLE IF NOT EXISTS posts (
    id BIGSERIAL PRIMARY KEY,
    author_account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    institution_id BIGINT NOT NULL,
    school_year_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    class_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    title VARCHAR(150) NULL,
    content TEXT NOT NULL,
    is_auto_generated BOOLEAN NOT NULL DEFAULT FALSE,
    is_hidden BOOLEAN NOT NULL DEFAULT FALSE,
    hidden_by_account_id BIGINT NULL REFERENCES accounts(id) ON DELETE SET NULL,
    hidden_at TIMESTAMP WITHOUT TIME ZONE NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITHOUT TIME ZONE NULL
);

-- Isolar por escola é o primeiro filtro de qualquer leitura do feed.
CREATE INDEX IF NOT EXISTS idx_posts_scoping
    ON posts(institution_id, class_id, course_id, school_year_id, is_hidden, created_at DESC);

CREATE TABLE IF NOT EXISTS post_reactions (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    type VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_post_account_reaction UNIQUE (post_id, account_id, type)
);

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

CREATE TABLE IF NOT EXISTS post_reports (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    reporter_account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    reason VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_post_reporter UNIQUE (post_id, reporter_account_id)
);

-- Janela deslizante por conta, no mesmo molde do chat_rate_limits: uma só
-- instrução upsert para a reserva ser atómica sob pedidos concorrentes.
CREATE TABLE IF NOT EXISTS feed_rate_limits (
    rate_key VARCHAR(64) PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts > 0),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_feed_rate_limits_updated_at
    ON feed_rate_limits (updated_at);
