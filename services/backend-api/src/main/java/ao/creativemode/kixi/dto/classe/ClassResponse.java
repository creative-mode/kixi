package ao.creativemode.kixi.dto.classe;

import ao.creativemode.kixi.model.Course;
import ao.creativemode.kixi.model.SchoolYear;

import java.time.LocalDateTime;

public record ClassResponse(
        String code,
        String grade,
        Long courseId,
        Long schoolYearId,
        Course course,
        SchoolYear schoolYear,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) { }
