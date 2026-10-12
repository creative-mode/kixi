CREATE TABLE user_stats (
    account_id BIGINT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    xp BIGINT NOT NULL DEFAULT 0 CHECK (xp >= 0),
    level INTEGER NOT NULL DEFAULT 1 CHECK (level >= 1),
    streak INTEGER NOT NULL DEFAULT 0 CHECK (streak >= 0),
    longest_streak INTEGER NOT NULL DEFAULT 0 CHECK (longest_streak >= 0),
    free_pauses INTEGER NOT NULL DEFAULT 1 CHECK (free_pauses >= 0),
    last_activity_date DATE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE badges (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(255) NOT NULL
);

CREATE TABLE user_badges (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    badge_id BIGINT NOT NULL REFERENCES badges(id),
    subject_id BIGINT REFERENCES subjects(id),
    unlocked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_user_badges_account_badge_subject
    ON user_badges (account_id, badge_id, COALESCE(subject_id, 0));
CREATE INDEX idx_user_badges_account_unlocked ON user_badges(account_id, unlocked_at DESC);

-- One row per simulation makes XP grants idempotent and stores the normalized
-- score needed to evaluate the three most recent attempts per subject.
CREATE TABLE simulation_xp_awards (
    simulation_id BIGINT PRIMARY KEY REFERENCES simulations(id) ON DELETE CASCADE,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    subject_id BIGINT REFERENCES subjects(id),
    score_percent NUMERIC(5, 2) NOT NULL CHECK (score_percent BETWEEN 0 AND 100),
    xp_awarded INTEGER NOT NULL CHECK (xp_awarded >= 1),
    awarded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_simulation_xp_awards_topic
    ON simulation_xp_awards(account_id, subject_id, awarded_at DESC, simulation_id DESC);

INSERT INTO badges (code, name, description) VALUES
    ('FIRST_SIMULATION', 'Primeira prova', 'Concluiu a primeira simulação.'),
    ('STREAK_30_DAYS', '30 dias de estudo', 'Manteve uma sequência de estudo durante 30 dias.'),
    ('TOPIC_MASTERED', 'Tema dominado', 'Obteve média de pelo menos 80% nas três simulações mais recentes de uma disciplina.');
