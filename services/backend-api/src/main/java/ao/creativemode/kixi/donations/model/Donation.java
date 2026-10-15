package ao.creativemode.kixi.donations.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** A statement offered to the platform by somebody who is not signed in (issue #252). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("statement_donations")
public class Donation {

    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String CONVERTED = "converted";

    @Id
    private Long id;

    @Column("reference")
    private String reference;

    @Column("donor_name")
    private String donorName;

    @Column("donor_email")
    private String donorEmail;

    @Column("institution_name")
    private String institutionName;

    @Column("subject_name")
    private String subjectName;

    @Column("class_grade")
    private String classGrade;

    @Column("school_year")
    private String schoolYear;

    @Column("exam_type")
    private String examType;

    @Column("notes")
    private String notes;

    @Column("consent_at")
    private LocalDateTime consentAt;

    @Column("consent_version")
    private String consentVersion;

    @Column("status")
    private String status;

    @Column("review_note")
    private String reviewNote;

    @Column("reviewed_by_account_id")
    private Long reviewedByAccountId;

    @Column("reviewed_at")
    private LocalDateTime reviewedAt;

    /** The statement made from this donation, once an administrator converts it. */
    @Column("statement_id")
    private Long statementId;

    /** SHA-256 of the caller's address with a pepper. Never the address itself. */
    @Column("client_hash")
    private String clientHash;

    @Column("created_at")
    private LocalDateTime createdAt;
}
