package ao.creativemode.kixi.donations.dto;

import java.time.LocalDateTime;
import java.util.List;

/** A donation with everything an administrator needs to decide. Admin only: it carries the donor's e-mail. */
public record DonationDetail(
        Long id,
        String reference,
        String status,
        String donorName,
        String donorEmail,
        String institutionName,
        String subjectName,
        String classGrade,
        String schoolYear,
        String examType,
        String notes,
        LocalDateTime consentAt,
        String consentVersion,
        String reviewNote,
        LocalDateTime reviewedAt,
        Long statementId,
        LocalDateTime createdAt,
        List<DonationFileInfo> files
) {

    public record DonationFileInfo(Long id, String originalName, String contentType, Long sizeBytes) { }
}
