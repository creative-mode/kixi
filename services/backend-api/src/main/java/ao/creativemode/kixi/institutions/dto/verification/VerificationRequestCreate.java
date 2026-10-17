package ao.creativemode.kixi.institutions.dto.verification;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerificationRequestCreate(
        @NotBlank(message = "Official name is required")
        @Size(max = 255, message = "Official name must not exceed 255 characters")
        String officialName,
        @NotBlank(message = "Registration number is required")
        @Size(max = 60, message = "Registration number must not exceed 60 characters")
        String registrationNumber,
        @NotBlank(message = "Contact email is required")
        @Email(message = "Contact email is not valid")
        @Size(max = 150, message = "Contact email must not exceed 150 characters")
        String contactEmail,
        @Size(max = 500, message = "Document URL must not exceed 500 characters")
        String documentUrl,
        @Size(max = 1000, message = "Notes must not exceed 1000 characters")
        String notes
) { }
