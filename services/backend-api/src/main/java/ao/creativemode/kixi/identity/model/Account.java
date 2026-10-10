package ao.creativemode.kixi.identity.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("accounts")
public class Account {

    @Id
    private Long id;

    @Column("username")
    private String username;

    @Column("email")
    private String email;

    @Column("password_hash")
    private String passwordHash;

    @Column("email_verified")
    private Boolean emailVerified;

    @Column("active")
    private Boolean active;

    /**
     * Issue #107: this account is entitled to the accessibility extra time,
     * i.e. +25% on the statement duration when the server closes a simulation.
     * Written only by an administrator through PATCH /api/v1/accounts/{id}/accessibility.
     */
    @Column("accessibility_extra_time")
    private Boolean accessibilityExtraTime = false;

    @Column("last_login")
    private LocalDateTime lastLogin;

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
        return this.deletedAt != null;
    }

    public void recordLogin() {
        this.lastLogin = LocalDateTime.now();
    }
}
