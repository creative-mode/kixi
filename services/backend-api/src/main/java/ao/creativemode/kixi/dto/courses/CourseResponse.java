package ao.creativemode.kixi.dto.courses;

import java.time.LocalDateTime;

public record CourseResponse(
    Long id,
    String code,
    String name,
    String description,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime deletedAt
) {}
