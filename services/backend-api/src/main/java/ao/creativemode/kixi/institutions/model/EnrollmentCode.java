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
 * A code an ADMIN issues for one class (#255), that a student trades for a self-enrollment.
 *
 * <p>{@code classId} is the only place this points at; the institution and school year are
 * not duplicated here because {@code classes} already carries both and a self-enroll always
 * resolves them through the class the code names. {@code uses} only ever grows, by the
 * atomic {@code UPDATE ... WHERE uses < max_uses RETURNING} in
 * {@link ao.creativemode.kixi.institutions.service.EnrollmentCodeService#redeem}, so two
 * redemptions racing for the last seat cannot both win.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("enrollment_codes")
public class EnrollmentCode {

    @Id
    private Long id;

    @Column("code")
    private String code;

    @Column("class_id")
    private Long classId;

    @Column("max_uses")
    private Integer maxUses;

    @Column("uses")
    private Integer uses = 0;

    @Column("expires_at")
    private LocalDateTime expiresAt;

    @Column("created_by_account_id")
    private Long createdByAccountId;

    @Column("revoked_at")
    private LocalDateTime revokedAt;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    public EnrollmentCode(String code, Long classId, Integer maxUses, LocalDateTime expiresAt, Long createdByAccountId) {
        this.code = code;
        this.classId = classId;
        this.maxUses = maxUses;
        this.uses = 0;
        this.expiresAt = expiresAt;
        this.createdByAccountId = createdByAccountId;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }

    public boolean isExhausted() {
        return maxUses != null && uses != null && uses >= maxUses;
    }

    /** Whether this code can still be redeemed, as far as a plain read can tell. */
    public boolean isValid() {
        return !isRevoked() && !isExpired() && !isExhausted();
    }
}
