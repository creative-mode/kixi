package ao.creativemode.kixi.dto.subject;

import java.time.LocalDateTime;

public record SubjectResponse(
        Long id,
        String code,
        String name,
        String short_name,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) { }
