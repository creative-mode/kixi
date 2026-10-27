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
    String error
) {}
