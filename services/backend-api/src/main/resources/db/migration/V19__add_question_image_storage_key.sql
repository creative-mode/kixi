ALTER TABLE question_images
    ADD COLUMN IF NOT EXISTS storage_key TEXT;

CREATE INDEX IF NOT EXISTS idx_question_images_storage_key
    ON question_images(storage_key)
    WHERE storage_key IS NOT NULL;
