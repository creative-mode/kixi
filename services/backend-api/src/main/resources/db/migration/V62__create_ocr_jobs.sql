CREATE TABLE ocr_jobs (
    id UUID PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    request_id UUID NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    page_count INTEGER NOT NULL DEFAULT 0 CHECK (page_count >= 0),
    completed_pages INTEGER NOT NULL DEFAULT 0 CHECK (completed_pages >= 0),
    current_page INTEGER NOT NULL DEFAULT 0 CHECK (current_page >= 0),
    event_type VARCHAR(16) NOT NULL DEFAULT 'queued' CHECK (event_type IN ('queued', 'prepared', 'rasterized', 'progress', 'result', 'error')),
    result_json JSONB,
    error_message VARCHAR(160),
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ,
    CONSTRAINT chk_ocr_job_completed_pages CHECK (completed_pages <= page_count OR page_count = 0)
);

CREATE UNIQUE INDEX uq_ocr_jobs_one_active_per_account
    ON ocr_jobs (account_id)
    WHERE status IN ('QUEUED', 'PROCESSING');

CREATE INDEX idx_ocr_jobs_claim_queue
    ON ocr_jobs (created_at, id)
    WHERE status = 'QUEUED';

CREATE INDEX idx_ocr_jobs_expiry
    ON ocr_jobs (expires_at)
    WHERE expires_at IS NOT NULL;

CREATE TABLE ocr_job_files (
    job_id UUID NOT NULL REFERENCES ocr_jobs(id) ON DELETE CASCADE,
    position SMALLINT NOT NULL CHECK (position >= 0),
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(128),
    content BYTEA NOT NULL,
    PRIMARY KEY (job_id, position)
);

CREATE TABLE ocr_job_worker_lock (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    owner UUID,
    lease_until TIMESTAMPTZ
);

INSERT INTO ocr_job_worker_lock (singleton) VALUES (TRUE);
