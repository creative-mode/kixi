package ao.creativemode.kixi.moderation.dto;

import java.time.LocalDateTime;

public record ReportResponse(
        Long id,
        String target,
        Long targetId,
        Long reporterAccountId,
        String reason,
        String note,
        LocalDateTime createdAt
) {}
