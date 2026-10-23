CREATE TABLE IF NOT EXISTS simulation_integrity_events (
    id BIGSERIAL PRIMARY KEY,
    simulation_id BIGINT NOT NULL REFERENCES simulations(id) ON DELETE CASCADE,
    kind VARCHAR(32) NOT NULL,
    question_id BIGINT,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_simulation_integrity_event_kind CHECK (
        kind IN ('TAB_HIDDEN', 'TAB_VISIBLE', 'WINDOW_BLUR', 'WINDOW_FOCUS', 'PASTE_BLOCKED', 'TUTOR_BLOCKED')
    ),
    CONSTRAINT chk_simulation_integrity_event_question CHECK (
        (kind = 'PASTE_BLOCKED' AND question_id IS NOT NULL)
        OR (kind <> 'PASTE_BLOCKED' AND question_id IS NULL)
    ),
    CONSTRAINT fk_simulation_integrity_event_question
        FOREIGN KEY (question_id) REFERENCES questions(id)
);

-- R__simulation_integrity_events.sql was used briefly before this versioned
-- migration. Keep upgrades idempotent for databases where that table already exists.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_simulation_integrity_event_question'
          AND conrelid = 'simulation_integrity_events'::regclass
    ) THEN
        ALTER TABLE simulation_integrity_events
            ADD CONSTRAINT fk_simulation_integrity_event_question
            FOREIGN KEY (question_id) REFERENCES questions(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_simulation_integrity_events_simulation_time
    ON simulation_integrity_events (simulation_id, occurred_at, id);
