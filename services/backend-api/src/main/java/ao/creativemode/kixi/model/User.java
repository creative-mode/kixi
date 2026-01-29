package ao.creativemode.kixi.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    private Long id;

    @Column("account_id")
    private Long accountId;

    /**
     * Relationship field: User belongs to one Account.
     * This field is not persisted in the database (marked as @Transient).
     * Must be loaded explicitly via repository/service layer.
     */
    @Transient
    private Account account;

    @Column("first_name")
    private String firstName;

    @Column("last_name")
    private String lastName;

    @Column("photo")
    private String photo;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

    public User(Long accountId, String firstName, String lastName) {
        this.accountId = accountId;
        this.firstName = firstName;
        this.lastName = lastName;
    }

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
