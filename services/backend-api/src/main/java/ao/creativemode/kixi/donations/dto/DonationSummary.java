package ao.creativemode.kixi.donations.dto;

import java.time.LocalDateTime;

/** A row of the review list. No e-mail and no notes: the list is for scanning. */
public record DonationSummary(
        Long id,
        String reference,
        String status,
        String institutionName,
        String subjectName,
        String examType,
        String classGrade,
        String schoolYear,
        int fileCount,
        LocalDateTime createdAt
) { }
