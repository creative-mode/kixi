package ao.creativemode.kixi.academic.dto.courses;

import java.time.LocalDateTime;

public record CourseResponse(
    Long id,
    /** Opaque reference to the school. The name is resolved by the institutions
     *  module, which owns schools; this module must not depend on it. */
    Long institutionId,
    String code,
    String name,
    String description,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime deletedAt
) {}
