-- Preserve OCR-detected printed grouping (for example, a curly brace) through save and reprint.
ALTER TABLE questions
    ADD COLUMN IF NOT EXISTS group_layout VARCHAR(16) NULL,
    ADD CONSTRAINT questions_group_layout_check
        CHECK (group_layout IS NULL OR group_layout IN ('lettered', 'brace'));
