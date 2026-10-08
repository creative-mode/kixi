package ao.creativemode.kixi.institutions.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/** A student (user with the STUDENT role) enrolled in an institution (N:N). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("institution_students")
public class InstitutionStudent {

    @Id
    private Long id;

    @Column("institution_id")
    private Long institutionId;

    @Column("user_id")
    private Long userId;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

    public InstitutionStudent(Long institutionId, Long userId) {
        this.institutionId = institutionId;
        this.userId = userId;
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
}
