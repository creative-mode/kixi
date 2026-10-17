package ao.creativemode.kixi.institutions.dto.verification;

import jakarta.validation.constraints.Size;

/** The reason is required when rejecting and optional when approving. */
public record VerificationReviewRequest(
        @Size(max = 500, message = "Note must not exceed 500 characters")
        String note
) { }
