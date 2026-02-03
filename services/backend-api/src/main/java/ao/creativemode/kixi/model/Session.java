package ao.creativemode.kixi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("sessions")
public class Session {
    @Id
    private Long id;

    @Column("account_id")
    private Long accountId;

    @Column("token")
    private String token;

    @Column("ip_address")
    private String ipAddress;

    @Column("expires_at")
    private LocalDateTime expiresAt;

    @Column("last_used")
    private LocalDateTime lastUsed;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

    @Transient
    private Account account;

    public void markAsDeleted() {
        this.deletedAt = LocalDateTime.now();
    }

    public void restore() {
        this.deletedAt = null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * Sets the account relationship and updates the foreign key.
     * In R2DBC, relationships must be managed manually.
     */
    public void setAccount(Account account) {
        this.account = account;
        this.accountId = account != null ? account.getId() : null;
    }
}