-- Keep the OCR layout metadata with the persisted image. Existing/manual images
-- remain valid with null provenance; OCR scores are normalized to [0, 1].
ALTER TABLE question_images
    ADD COLUMN IF NOT EXISTS ocr_kind VARCHAR(24) NULL,
    ADD COLUMN IF NOT EXISTS ocr_page_index INTEGER NULL,
    ADD COLUMN IF NOT EXISTS ocr_score DOUBLE PRECISION NULL,
    ADD CONSTRAINT question_images_ocr_kind_check
        CHECK (ocr_kind IS NULL OR ocr_kind IN ('figure', 'formula', 'table')),
    ADD CONSTRAINT question_images_ocr_page_index_check
        CHECK (ocr_page_index IS NULL OR ocr_page_index >= 0),
    ADD CONSTRAINT question_images_ocr_score_check
        CHECK (ocr_score IS NULL OR (ocr_score >= 0 AND ocr_score <= 1));
