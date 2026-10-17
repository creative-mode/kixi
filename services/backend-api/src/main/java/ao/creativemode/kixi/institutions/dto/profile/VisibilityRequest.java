package ao.creativemode.kixi.institutions.dto.profile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VisibilityRequest(
        @NotBlank(message = "Visibility is required")
        @Pattern(regexp = "public|school|private", message = "Visibility must be public, school or private")
        String visibility
) { }
