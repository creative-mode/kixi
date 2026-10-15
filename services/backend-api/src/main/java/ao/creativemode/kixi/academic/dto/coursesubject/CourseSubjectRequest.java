package ao.creativemode.kixi.academic.dto.coursesubject;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** A subject to put in a course. {@code grade} null means every grade of the course. */
public record CourseSubjectRequest(
        @NotNull(message = "The subject is required")
        Long subjectId,
        @Positive(message = "The grade must be greater than zero")
        Integer grade
) { }
