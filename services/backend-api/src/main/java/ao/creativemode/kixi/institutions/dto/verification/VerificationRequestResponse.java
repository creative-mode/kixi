package ao.creativemode.kixi.institutions.dto.verification;

import java.time.LocalDateTime;

public record VerificationRequestResponse(
        Long id,
        Long institutionId,
        String officialName,
        String registrationNumber,
        String contactEmail,
        String documentUrl,
        String notes,
        String status,
        String reviewNote,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt
) { }
