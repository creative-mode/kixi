package ao.creativemode.kixi.institutions.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** What a school declared when it asked for the verification badge (issue #332). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("institution_verification_requests")
public class InstitutionVerificationRequest {

    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";

    @Id
    private Long id;

    @Column("institution_id")
    private Long institutionId;

    @Column("official_name")
    private String officialName;

    @Column("registration_number")
    private String registrationNumber;

    @Column("contact_email")
    private String contactEmail;

    @Column("document_url")
    private String documentUrl;

    @Column("notes")
    private String notes;

    @Column("status")
    private String status;

    @Column("review_note")
    private String reviewNote;

    @Column("requested_by_account_id")
    private Long requestedByAccountId;

    @Column("reviewed_by_account_id")
    private Long reviewedByAccountId;

    @Column("reviewed_at")
    private LocalDateTime reviewedAt;

    @Column("created_at")
    private LocalDateTime createdAt;
}
