-- The AI tutor (issue #114). A session binds one account to one statement:
-- the tutor only ever answers from that statement's material, so the binding
-- is what makes "restrito aos enunciados oficiais" enforceable at read time.
--
-- Version note: this takes the next free number after V30. If another branch
-- claims V31 first, renumber on rebase — MigrationInventoryTest requires a
-- contiguous sequence and fails the build on a collision.
CREATE TABLE IF NOT EXISTS chat_sessions (
    id           BIGSERIAL PRIMARY KEY,
    account_id   BIGINT NOT NULL,
    statement_id BIGINT NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,

    CONSTRAINT fk_chat_sessions_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT fk_chat_sessions_statement FOREIGN KEY (statement_id) REFERENCES statements(id)
);

CREATE INDEX idx_chat_sessions_account ON chat_sessions(account_id);

CREATE TABLE IF NOT EXISTS chat_messages (
    id          BIGSERIAL PRIMARY KEY,
    session_id  BIGINT NOT NULL,
    role        VARCHAR(20) NOT NULL,
    content     TEXT NOT NULL,
    model       VARCHAR(100),
    tokens_used INTEGER,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,

    CONSTRAINT fk_chat_messages_session FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE,
    CONSTRAINT chk_chat_message_role CHECK (role IN ('USER', 'ASSISTANT'))
);

CREATE INDEX idx_chat_messages_session ON chat_messages(session_id);

-- Per-account sliding window for tutor messages. Same shape as
-- registration_rate_limits, but its own table: the tutor's limits must never
-- read as registration attempts, and registration must not see chat traffic.
CREATE TABLE IF NOT EXISTS chat_rate_limits (
    rate_key VARCHAR(64) PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts > 0),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_chat_rate_limits_updated_at
    ON chat_rate_limits (updated_at);