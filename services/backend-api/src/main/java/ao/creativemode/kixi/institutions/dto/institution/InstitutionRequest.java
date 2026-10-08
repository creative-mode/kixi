package ao.creativemode.kixi.institutions.dto.institution;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InstitutionRequest(
        @NotBlank(message = "Code is required")
        @Size(max = 50, message = "Code must not exceed 50 characters")
        String code,
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must not exceed 255 characters")
        String name,
        @Size(max = 50, message = "Short name must not exceed 50 characters")
        @JsonProperty("short_name")
        String shortName,
        String logo
) { }
