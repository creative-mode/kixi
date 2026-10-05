package ao.creativemode.kixi.institutions.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/** A subject taught by an institution. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("institution_subjects")
public class InstitutionSubject {

    @Id
    private Long id;

    @Column("institution_id")
    private Long institutionId;

    @Column("subject_id")
    private Long subjectId;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

    public InstitutionSubject(Long institutionId, Long subjectId) {
        this.institutionId = institutionId;
        this.subjectId = subjectId;
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
