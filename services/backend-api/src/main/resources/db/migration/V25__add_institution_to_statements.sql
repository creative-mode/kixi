ALTER TABLE statements ADD COLUMN IF NOT EXISTS institution_id BIGINT;
ALTER TABLE statements ADD CONSTRAINT fk_statements_institution FOREIGN KEY (institution_id) REFERENCES institutions(id);
CREATE INDEX idx_statements_institution ON statements (institution_id);
