package ao.creativemode.kixi.client;

import org.springframework.http.MediaType;

/**
 * Immutable upload retained for OCR persistence. Keeping the bytes here lets
 * the backend associate OCR regions with the exact source page it submitted.
 */
public record OcrUploadedFile(
        String filename,
        MediaType contentType,
        byte[] content) {

    public OcrUploadedFile {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename is required");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("File content cannot be empty");
        }
    }
}
