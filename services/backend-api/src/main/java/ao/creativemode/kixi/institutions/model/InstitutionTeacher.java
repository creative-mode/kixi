package ao.creativemode.kixi.institutions.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/** A teacher affiliated with an institution (N:N). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("institution_teachers")
public class InstitutionTeacher {

    @Id
    private Long id;

    @Column("institution_id")
    private Long institutionId;

    @Column("teacher_id")
    private Long teacherId;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

    public InstitutionTeacher(Long institutionId, Long teacherId) {
        this.institutionId = institutionId;
        this.teacherId = teacherId;
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
