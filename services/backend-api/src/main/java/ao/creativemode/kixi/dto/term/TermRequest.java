package ao.creativemode.kixi.dto.term;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TermRequest(
        @NotBlank(message = "Name is required")
        String name,

        @NotNull(message = "Number is required")
        @Positive(message = "Number must be positive")
        int number
) {}