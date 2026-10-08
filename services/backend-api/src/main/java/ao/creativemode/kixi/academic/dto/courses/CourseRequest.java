package ao.creativemode.kixi.academic.dto.courses;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CourseRequest(
        @NotBlank(message = "Code is required")
        @Size(min = 2, max = 50, message = "Code must be between 2 and 50 characters")
        String code,

        @NotBlank(message = "Name is required")
        @Size(min = 3, max = 255, message = "Name must be between 3 and 255 characters")
        String name,

        @Size(max = 5000, message = "Description cannot exceed 5000 characters")
        String description,

        /** The school that offers this course. */
        @NotNull(message = "Institution is required") Long institutionId
) {}