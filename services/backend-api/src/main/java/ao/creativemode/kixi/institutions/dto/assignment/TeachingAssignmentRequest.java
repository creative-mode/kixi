package ao.creativemode.kixi.institutions.dto.assignment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TeachingAssignmentRequest(
    @NotNull(message = "The teacher is required")
    Long teacherId,

    @NotNull(message = "The class is required")
    Long classId,

    @NotNull(message = "The subject is required")
    Long subjectId,

    @NotNull(message = "The school year is required")
    Long schoolYearId,

    @Size(max = 100, message = "The tutor style must not exceed 100 characters")
    String tutorStyle
) { }
