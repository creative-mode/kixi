package ao.creativemode.kixi.institutions.model;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A student's enrollment in a class for a school year.
 *
 * <p>Invariants (also enforced by {@code V28}): one <em>active</em> enrollment
 * per account and school year. A cancelled enrollment is soft-deleted and a
 * new one for the same year restores or replaces it.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("enrollments")
public class Enrollment {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    private Long id;

    @Column("account_id")
    private Long accountId;

    @Column("class_id")
    private Long classId;

    @Column("school_year_id")
    private Long schoolYearId;

    @Column("status")
    private String status = STATUS_ACTIVE;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

    public Enrollment(Long accountId, Long classId, Long schoolYearId) {
        this.accountId = accountId;
        this.classId = classId;
        this.schoolYearId = schoolYearId;
        this.status = STATUS_ACTIVE;
    }

    public void markAsDeleted() {
        this.deletedAt = LocalDateTime.now();
        this.status = STATUS_CANCELLED;
    }

    public void restore() {
        this.deletedAt = null;
        this.status = STATUS_ACTIVE;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * Whether this enrollment still seats the student.
     *
     * <p>A cancelled enrollment is not a seat, whatever the query that fetched it
     * filtered on. Issue #117 ranks a cohort over ACTIVE students only, so the code that
     * decides whether the caller belongs has to ask this rather than assume.</p>
     */
    public boolean isActive() {
        return STATUS_ACTIVE.equals(status) && deletedAt == null;
    }
}
