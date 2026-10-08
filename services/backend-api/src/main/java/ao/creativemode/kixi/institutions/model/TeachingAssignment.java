package ao.creativemode.kixi.institutions.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * A teacher responsible for a subject in a class during a school year. Belonging
 * to an institution says where a teacher works; this says what they teach, and is
 * what scopes the statements they are allowed to build.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("teaching_assignments")
public class TeachingAssignment {

    @Id
    private Long id;

    @Column("teacher_id")
    private Long teacherId;

    @Column("class_id")
    private Long classId;

    @Column("subject_id")
    private Long subjectId;

    @Column("school_year_id")
    private Long schoolYearId;

    /** Free text, e.g. "Professor titular" or "Professor adjunto". */
    @Column("tutor_style")
    private String tutorStyle;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

    public TeachingAssignment(Long teacherId, Long classId, Long subjectId, Long schoolYearId) {
        this.teacherId = teacherId;
        this.classId = classId;
        this.subjectId = subjectId;
        this.schoolYearId = schoolYearId;
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
