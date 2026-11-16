-- Visibility of a student inside the relative ranking (issue #611).
--
-- The class/school/friends tables publish who a student sat beside, to people they
-- sat beside. That is the consent the table stands on, but a student who does not
-- want to be ranked at all should be able to leave it, or stay in it anonymously.
--
-- One row per (account, scope): a preference chosen once and kept, like the
-- cross-institution consent. `scope` is the same vocabulary the endpoint takes —
-- mine is `class`, `school`, `friends` or `global` — and `preference` is
-- `visible` (the default when no row exists), `anonymous` (shown as «Um colega»),
-- or `hidden` (left out of the table for everyone but the owner).
--
-- The row is not consulted for consent to publish: hiding is not a dodge of the
-- cross-institution gate, which still has to be satisfied before scope=global is
-- served at all.
CREATE TABLE IF NOT EXISTS leaderboard_visibility (
    id                 BIGSERIAL PRIMARY KEY,
    account_id         BIGINT NOT NULL,
    scope              VARCHAR(16) NOT NULL,
    preference         VARCHAR(16) NOT NULL,
    consented_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    revoked_at         TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    created_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,

    CONSTRAINT leaderboard_visibility_scope_check
        CHECK (scope IN ('class', 'school', 'friends', 'global')),
    CONSTRAINT leaderboard_visibility_preference_check
        CHECK (preference IN ('visible', 'anonymous', 'hidden'))
);

-- One row per (account, scope): the unique index is what makes "my choice does not
-- leak to what another row says" a database fact, not an ordering rule.
CREATE UNIQUE INDEX IF NOT EXISTS leaderboard_visibility_account_scope
    ON leaderboard_visibility (account_id, scope);
