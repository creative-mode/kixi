package ao.creativemode.kixi.institutions.dto.enrollment;

import java.time.LocalDateTime;

public record EnrollmentCodeResponse(
        Long id,
        String code,
        Long classId,
        Integer maxUses,
        Integer uses,
        LocalDateTime expiresAt,
        LocalDateTime revokedAt,
        LocalDateTime createdAt
) { }
