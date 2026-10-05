package ao.creativemode.kixi.institutions.dto.assignment;

import java.time.LocalDateTime;

public record TeachingAssignmentResponse(
        Long id,
        Long teacherId,
        Long classId,
        Long subjectId,
        Long schoolYearId,
        String tutorStyle,
        LocalDateTime createdAt,
        LocalDateTime deletedAt
) { }
