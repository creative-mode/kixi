package ao.creativemode.kixi.chat.dto;

import java.time.LocalDateTime;

public record ChatSessionResponse(
    Long id,
    Long statementId,
    LocalDateTime createdAt
) {}
