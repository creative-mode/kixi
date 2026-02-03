package ao.creativemode.kixi.model;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

@Data
@Table("statement")
public class Statement {
    @Id
    private Long id;

    @Column("exam_type")
    private String examType;

    @Column("duration_minutes")
    private Integer durationMinutes;

    @Column("variant")
    private String variant;

    @Column("title")
    private String title;

    @Column("instructions")
    private String instructions;

    @Column("total_max_score")
    private Integer totalMaxScore;

    @Column("school_year_id")
    private Long schoolYearId;

    @Column("term_id")
    private Long termId;

    @Column("subject_id")
    private Long subjectId;

    @Column("class_id")
    private Long classId;

    @Column("course_id")
    private Long courseId;

    @Column("create_by")
    private Long createdBy;

    @Column("visible")
    private Boolean visible;

    @CreatedDate
    @Column("create_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("update_at")
    private LocalDateTime updatedAt;

    @Column("delete_at")
    private LocalDateTime deletedAt;
}
