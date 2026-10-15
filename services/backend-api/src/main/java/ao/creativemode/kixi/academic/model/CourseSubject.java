package ao.creativemode.kixi.academic.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * One entry of a course's curriculum: a subject the course has, for one grade or for all of them.
 * A subject can be in many courses, of many schools; the same course never has it twice for the
 * same grade.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("course_subjects")
public class CourseSubject {

    @Id
    private Long id;

    @Column("course_id")
    private Long courseId;

    @Column("subject_id")
    private Long subjectId;

    /** The grade (classe) the subject applies to; null means every grade of the course. */
    @Column("grade")
    private Integer grade;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

    /** Whether this entry covers a class of the given grade. */
    public boolean appliesTo(Integer classGrade) {
        return grade == null || grade.equals(classGrade);
    }
}
