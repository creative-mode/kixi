package ao.creativemode.kixi.feed.dto.postReport;

import java.time.LocalDateTime;

public record PostReportResponse(
        Long id,
        Long postId,
        Long reporterAccountId,
        String reason,
        LocalDateTime createdAt
) {}