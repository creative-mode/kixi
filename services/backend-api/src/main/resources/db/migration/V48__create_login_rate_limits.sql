CREATE TABLE login_rate_limits (
    rate_key VARCHAR(64) PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts > 0),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_login_rate_limits_updated_at
    ON login_rate_limits (updated_at);