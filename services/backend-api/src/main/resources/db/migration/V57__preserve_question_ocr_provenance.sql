-- Preserve OCR provenance across the manager save/reload boundary.
ALTER TABLE questions
    ADD COLUMN IF NOT EXISTS raw_text TEXT NULL,
    ADD COLUMN IF NOT EXISTS ocr_formulas TEXT NULL,
    ADD COLUMN IF NOT EXISTS ocr_reviewed BOOLEAN NOT NULL DEFAULT FALSE;
