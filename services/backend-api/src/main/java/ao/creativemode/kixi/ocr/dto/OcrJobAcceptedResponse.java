package ao.creativemode.kixi.ocr.dto;

import java.util.UUID;

public record OcrJobAcceptedResponse(UUID jobId, UUID requestId, String status, String statusUrl) {}
