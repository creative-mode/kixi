-- Um código estável para o erro de uma importação OCR falhada (OCR_NO_TEXT, OCR_UNREADABLE, OCR_FILE_CORRUPT, …): o frontend mapeia-o
-- para o seu texto; error_message guarda a mensagem em português.
ALTER TABLE ocr_jobs ADD COLUMN error_code VARCHAR(40);
