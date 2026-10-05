package ao.creativemode.kixi.identity.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * A teacher is a person profile. It only gets access to the platform when an
 * {@link Account} is linked through {@code accountId} (granted and revoked by
 * the teacher service); without one the teacher exists as a record only.
 */
@Table("teachers")
@Getter
@Setter
@NoArgsConstructor
public class Teacher {

    @Id
    private Long id;

    @Column("account_id")
    private Long accountId;

    @Column("first_name")
    private String firstName;

    @Column("last_name")
    private String lastName;

    @Column("email")
    private String email;

    @Column("photo")
    private String photo;

    @Column("specialty")
    private String specialty;

    @Column("employee_number")
    private String employeeNumber;

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

    public boolean hasAccess() {
        return accountId != null;
    }
}
