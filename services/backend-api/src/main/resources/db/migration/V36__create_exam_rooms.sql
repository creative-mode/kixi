CREATE TABLE exam_rooms (
    id BIGSERIAL PRIMARY KEY,
    statement_id BIGINT NOT NULL,
    teacher_account_id BIGINT NOT NULL,
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
    duration_minutes INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_exam_rooms_statement FOREIGN KEY (statement_id) REFERENCES statements(id),
    CONSTRAINT fk_exam_rooms_teacher FOREIGN KEY (teacher_account_id) REFERENCES accounts(id),
    CONSTRAINT chk_exam_rooms_window CHECK (ends_at > starts_at),
    CONSTRAINT chk_exam_rooms_duration CHECK (duration_minutes > 0),
    CONSTRAINT chk_exam_rooms_status CHECK (status IN ('DRAFT', 'OPEN', 'RUNNING', 'CLOSED'))
);

CREATE INDEX idx_exam_rooms_teacher ON exam_rooms(teacher_account_id);
CREATE INDEX idx_exam_rooms_status ON exam_rooms(status);

CREATE TABLE exam_room_participants (
    id BIGSERIAL PRIMARY KEY,
    exam_room_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    simulation_id BIGINT,
    joined_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_exam_room_participants_room FOREIGN KEY (exam_room_id) REFERENCES exam_rooms(id) ON DELETE CASCADE,
    CONSTRAINT fk_exam_room_participants_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT fk_exam_room_participants_simulation FOREIGN KEY (simulation_id) REFERENCES simulations(id),
    CONSTRAINT uq_exam_room_participant UNIQUE (exam_room_id, account_id)
);

CREATE INDEX idx_exam_room_participants_account ON exam_room_participants(account_id);

ALTER TABLE simulations ADD COLUMN exam_room_id BIGINT;
ALTER TABLE simulations ADD COLUMN exam_room_duration_minutes INTEGER;
ALTER TABLE simulations ADD CONSTRAINT fk_simulations_exam_room
    FOREIGN KEY (exam_room_id) REFERENCES exam_rooms(id);
CREATE INDEX idx_simulations_exam_room ON simulations(exam_room_id);
CREATE UNIQUE INDEX uq_simulations_exam_room_account
    ON simulations(exam_room_id, account_id) WHERE exam_room_id IS NOT NULL;
