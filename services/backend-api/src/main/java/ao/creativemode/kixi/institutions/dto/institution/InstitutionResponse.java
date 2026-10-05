package ao.creativemode.kixi.institutions.dto.institution;

import java.time.LocalDateTime;

public record InstitutionResponse(
        Long id,
        String code,
        String name,
        String short_name,
        String logo,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) { }
