-- Create table for storing question-related images
CREATE TABLE question_images (
    id BIGSERIAL PRIMARY KEY,
    question_id BIGSERIAL NOT NULL,
    image_url TEXT NOT NULL,
    caption TEXT,
    order_index INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,

    -- Foreign key constraint linking to the questions table
    CONSTRAINT fk_question_images_question 
        FOREIGN KEY (question_id) 
        REFERENCES questions (id) 
        ON DELETE CASCADE
);

-- Index to optimize lookups and filtering by question_id
CREATE INDEX idx_question_images_question_id ON question_images(question_id);