package ao.creativemode.kixi.dto.subject;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubjectRequest(
        @NotBlank(message = "Code is required")
        @NotNull(message = "Code cannot be null")
        String code,
        @NotBlank(message = "Name is required")
        @NotNull(message = "Code cannot be null")
        String name,
        @Size(max = 10, message = "Short name must not exceed 10 characters")
        @JsonProperty("short_name")
        String shortName
) { }
