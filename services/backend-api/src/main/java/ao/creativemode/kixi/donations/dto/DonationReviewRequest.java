package ao.creativemode.kixi.donations.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The decision of an administrator: {@code approved} or {@code rejected}, with an optional note. */
public record DonationReviewRequest(
        @NotBlank(message = "The status is required")
        String status,
        @Size(max = 500, message = "The note must not exceed 500 characters")
        String note
) { }
