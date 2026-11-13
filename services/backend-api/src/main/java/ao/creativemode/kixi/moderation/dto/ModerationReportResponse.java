package ao.creativemode.kixi.moderation.dto;

import java.time.LocalDateTime;

/** Uma denúncia na fila do administrador, com os nomes já resolvidos (kixi#591). */
public record ModerationReportResponse(
        Long id,
        String target,
        Long targetId,
        String targetName,
        Long reporterAccountId,
        String reporterName,
        String reason,
        String note,
        String status,
        LocalDateTime createdAt,
        LocalDateTime decidedAt,
        String decidedBy,
        String decisionNote
) {}
