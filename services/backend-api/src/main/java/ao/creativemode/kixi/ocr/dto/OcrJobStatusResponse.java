package ao.creativemode.kixi.ocr.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record OcrJobStatusResponse(
    UUID jobId,
    UUID requestId,
    String status,
    int pageCount,
    int completedPages,
    int currentPage,
    String eventType,
    JsonNode result,
    /** The message as it always was ("OCR processing failed"); the frontend maps it (kixi-frontend#841). */
    String error,
    /** A stable code for the client to map to its own text: {@code OCR_NO_TEXT}, {@code OCR_UNREADABLE}, … (see OcrProblem). */
    String errorCode
) {
    /** The shape before the code existed. */
    public OcrJobStatusResponse(UUID jobId, UUID requestId, String status, int pageCount, int completedPages, int currentPage,
            String eventType, JsonNode result, String error) {
        this(jobId, requestId, status, pageCount, completedPages, currentPage, eventType, result, error, null);
    }
}
