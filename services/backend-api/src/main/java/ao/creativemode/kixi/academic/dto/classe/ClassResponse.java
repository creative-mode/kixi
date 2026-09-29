package ao.creativemode.kixi.academic.dto.classe;

import ao.creativemode.kixi.academic.model.Course;
import ao.creativemode.kixi.academic.model.SchoolYear;
import java.time.LocalDateTime;

public record ClassResponse(
    Long id,
    String code,
    Integer grade,
    Course course,
    SchoolYear schoolYear,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime deletedAt
) {}
