package ao.creativemode.kixi.dto.schoolyears;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SchoolYearRequest(
        @NotNull(message = "Start year is required")
        @Positive(message = "Start year must be positive")
        Integer startYear,

        @NotNull(message = "End year is required")
        @Positive(message = "End year must be positive")
        Integer endYear
) {}
