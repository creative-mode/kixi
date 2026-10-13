-- Friendships: the cohort behind `GET /api/v1/leaderboard?scope=friends` (issue #121).
--
-- The pair is stored canonically (low, high) rather than as requester/addressee,
-- because a friendship is undirected and the two directions must never become two
-- rows. `requested_by` is kept separately — a friend request has an author, a
-- friendship does not. A CHECK holds the ordering, so a row cannot be written with
-- the pair the wrong way round and quietly become invisible to half the queries.
CREATE TABLE IF NOT EXISTS friendships (
    id           BIGSERIAL PRIMARY KEY,
    account_low  BIGINT NOT NULL,
    account_high BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at   TIMESTAMP WITH TIME ZONE DEFAULT NULL,

    CONSTRAINT uq_friendship_pair UNIQUE (account_low, account_high),
    CONSTRAINT chk_friendship_pair_ordered CHECK (account_low < account_high),
    CONSTRAINT chk_friendship_not_self CHECK (account_low <> account_high),
    CONSTRAINT chk_friendship_status CHECK (status IN ('PENDING', 'ACCEPTED')),
    CONSTRAINT fk_friendship_low FOREIGN KEY (account_low) REFERENCES accounts(id),
    CONSTRAINT fk_friendship_high FOREIGN KEY (account_high) REFERENCES accounts(id),
    CONSTRAINT fk_friendship_requester FOREIGN KEY (requested_by) REFERENCES accounts(id)
);

-- "Who are my friends" and "who asked me" are the two questions the ranking and the
-- inbox ask, and both go through the caller's own id.
CREATE INDEX IF NOT EXISTS idx_friendships_low
    ON friendships (account_low)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_friendships_high
    ON friendships (account_high)
    WHERE deleted_at IS NULL;

-- A friend request that nobody answered is noise after a while; the inbox reads this.
CREATE INDEX IF NOT EXISTS idx_friendships_pending
    ON friendships (status, updated_at)
    WHERE deleted_at IS NULL AND status = 'PENDING';