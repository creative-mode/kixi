package ao.creativemode.kixi.academic.model;

import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("classes")
public class Class {

    @Id
    private Long id;

    @Column("code")
    private String code;

    @Column("grade")
    private Integer grade;

    @Column("course_id")
    private Long courseId;

    @Column("school_year_id")
    private Long schoolYearId;

    /**
     * The school this class sits in. The backend requires it to equal the course's
     * institution, enforced by a composite foreign key, so the two cannot drift.
     * It is an opaque id here: this module does not depend on the institutions one.
     */
    @Column("institution_id")
    private Long institutionId;

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
