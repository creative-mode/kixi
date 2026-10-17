package ao.creativemode.kixi.institutions.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/** A school. Its logo and name are what an exam sheet changes from one school to another. */
@Table("institutions")
@Getter
@Setter
@NoArgsConstructor
public class Institution {

    @Id
    private Long id;

    @Column("code")
    private String code;

    @Column("name")
    private String name;

    @Column("short_name")
    private String shortName;

    /** URL or data URI of the school logo. */
    @Column("logo")
    private String logo;

    /** Set only by an administrator after reviewing a verification request (issue #332). */
    @Column("verified")
    private boolean verified;

    @Column("verified_at")
    private LocalDateTime verifiedAt;

    @Column("verified_by_account_id")
    private Long verifiedByAccountId;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

    public void markAsDeleted() {
        this.deletedAt = LocalDateTime.now();
    }

    public void restore() {
        this.deletedAt = null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
