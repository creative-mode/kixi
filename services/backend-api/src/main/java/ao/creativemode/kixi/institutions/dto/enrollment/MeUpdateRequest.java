package ao.creativemode.kixi.institutions.dto.enrollment;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Size;

public record MeUpdateRequest(
        @Size(max = 100, message = "First name must not exceed 100 characters")
        @JsonProperty("first_name")
        String firstName,
        @Size(max = 100, message = "Last name must not exceed 100 characters")
        @JsonProperty("last_name")
        String lastName,
        @Size(max = 500, message = "Photo must not exceed 500 characters")
        String photo
) { }
