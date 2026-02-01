package ao.creativemode.kixi.model;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

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


    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getExamType() {
        return examType;
    }

    public void setExamType(String examType) {
        this.examType = examType;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public String getVariant() {
        return variant;
    }

    public void setVariant(String variant) {
        this.variant = variant;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public Integer getTotalMaxScore() {
        return totalMaxScore;
    }

    public void setTotalMaxScore(Integer totalMaxScore) {
        this.totalMaxScore = totalMaxScore;
    }

    public Long getSchoolYearId() {
        return schoolYearId;
    }

    public void setSchoolYearId(Long schoolYearId) {
        this.schoolYearId = schoolYearId;
    }

    public Long getTermId() {
        return termId;
    }

    public void setTermId(Long termId) {
        this.termId = termId;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Long subjectId) {
        this.subjectId = subjectId;
    }

    public Long getClassId() {
        return classId;
    }

    public void setClassId(Long classId) {
        this.classId = classId;
    }

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Boolean getVisible() {
        return visible;
    }

    public void setVisible(Boolean visible) {
        this.visible = visible;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Statement(){
    }
}
