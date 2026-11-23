package ao.creativemode.kixi.moderation.dto;

import java.time.LocalDateTime;

/** Uma linha do registo de auditoria da moderação: quem decidiu o quê, quando e porquê (kixi#591). */
public record AuditEntryResponse(
        Long id,
        LocalDateTime at,
        String actor,
        String action,
        String target,
        Long targetId,
        String targetName,
        Long reportId,
        String note
) {}
