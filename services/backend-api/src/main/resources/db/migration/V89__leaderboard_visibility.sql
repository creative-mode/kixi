-- Per-scope ranking visibility (issue #611).
--
-- A student can appear in the class, school and friends rankings under the masked name
-- used today (VISIBLE), as the neutral label "Um colega" (ANONYMOUS), or not appear to
-- peers at all (OUT). The student still sees their own position either way.
--
-- Absence of a row means VISIBLE: the ranking existed before this preference did, and
-- making everyone disappear on deploy would be a change nobody asked for. One row per
-- account and scope, so a student can hide from the class and stay in the school rank.
--
-- The foreign key cascades because this is an account-scoped preference, not a
-- historical record: a permanently deleted account must not be kept alive by it
-- (AccountService.hardDelete). Other account-scoped tables (notification_reads,
-- moderation reports) use the same policy.
--
-- `scope=global` is deliberately absent. It is already opt-in through
-- `leaderboard_consents.cross_institution` (ADR-0013), and its peers are always labelled
-- "Aluno de outra escola"; a second switch there would have to disagree with the first.
CREATE TABLE IF NOT EXISTS leaderboard_visibility (
    id                 BIGSERIAL PRIMARY KEY,
    account_id         BIGINT NOT NULL,
    scope              VARCHAR(10) NOT NULL,
    ranking_visibility VARCHAR(10) NOT NULL DEFAULT 'VISIBLE',
    created_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,

    CONSTRAINT uq_leaderboard_visibility_account_scope UNIQUE (account_id, scope),
    CONSTRAINT fk_leaderboard_visibility_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT ck_leaderboard_visibility_scope
        CHECK (scope IN ('CLASS', 'SCHOOL', 'FRIENDS')),
    CONSTRAINT ck_leaderboard_visibility_value
        CHECK (ranking_visibility IN ('VISIBLE', 'ANONYMOUS', 'OUT'))
);
