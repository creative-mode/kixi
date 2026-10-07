package ao.creativemode.kixi.academic.dto.courses;

import jakarta.validation.constraints.NotNull;

public record CourseRequest(
        @NotNull(message = "Code cannot be null") String code,
        @NotNull(message = "Name is required") String name,
        String description,
        /** The school that offers this course. */
        @NotNull(message = "Institution is required") Long institutionId
) {}
