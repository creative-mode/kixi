package ao.creativemode.kixi.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Statement entity representing an exam paper/test in the system.
 *
 * This entity stores metadata about exam papers including their type,
 * duration, variant, and associated references to school year, term,
 * subject, and class.
 *
 * The actual questions are stored in a separate Question entity with
 * a foreign key reference to this statement.
 */
@Table("statements")
public class Statement {

    @Id
    private Long id;

    /**
     * Type of examination (e.g., "Avaliação Periódica", "Exame Final", "Teste Sumativo")
     */
    @Column("exam_type")
    private String examType;

    /**
     * Duration of the exam in minutes
     */
    @Column("duration_minutes")
    private Integer durationMinutes;

    /**
     * Exam variant (e.g., "A", "B", "C")
     */
    @Column("variant")
    private String variant;

    /**
     * Title of the exam/statement
     */
    @Column("title")
    private String title;

    /**
     * Instructions for the exam
     */
    @Column("instructions")
    private String instructions;

    /**
     * Total maximum score for the entire exam
     */
    @Column("total_max_score")
    private Double totalMaxScore;

    /**
     * Reference to the school year
     */
    @Column("school_year_id")
    private Long schoolYearId;

    /**
     * Reference to the term/trimester
     */
    @Column("term_id")
    private Long termId;

    /**
     * Reference to the subject
     */
    @Column("subject_id")
    private Long subjectId;

    /**
     * Reference to the class
     */
    @Column("class_id")
    private Long classId;

    /**
     * Reference to the course (optional)
     */
    @Column("course_id")
    private Long courseId;

    /**
     * Reference to the user who created this statement
     */
    @Column("created_by")
    private Long createdBy;

    /**
     * Visibility flag (true if the statement is visible to students)
     */
    @Column("visible")
    private Boolean visible;

    /**
     * Flag indicating if this statement needs human review (e.g., low OCR confidence)
     */
    @Column("needs_review")
    private Boolean needsReview;

    /**
     * OCR confidence score (0.0 - 1.0)
     */
    @Column("ocr_confidence")
    private Double ocrConfidence;

    /**
     * Original OCR request ID for tracking
     */
    @Column("ocr_request_id")
    private String ocrRequestId;

    /**
     * Source of the statement (e.g., "manual", "ocr", "import")
     */
    @Column("source")
    private String source;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

    // Constructors

    public Statement() {
        this.visible = false;
        this.needsReview = false;
        this.source = "manual";
    }

    public Statement(String examType, String title) {
        this();
        this.examType = examType;
        this.title = title;
    }

    // Getters and Setters

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

    public Double getTotalMaxScore() {
        return totalMaxScore;
    }

    public void setTotalMaxScore(Double totalMaxScore) {
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

    public Boolean getNeedsReview() {
        return needsReview;
    }

    public void setNeedsReview(Boolean needsReview) {
        this.needsReview = needsReview;
    }

    public Double getOcrConfidence() {
        return ocrConfidence;
    }

    public void setOcrConfidence(Double ocrConfidence) {
        this.ocrConfidence = ocrConfidence;
    }

    public String getOcrRequestId() {
        return ocrRequestId;
    }

    public void setOcrRequestId(String ocrRequestId) {
        this.ocrRequestId = ocrRequestId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
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

    // Utility methods

    /**
     * Mark this statement as deleted (soft delete)
     */
    public void markAsDeleted() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * Restore a soft-deleted statement
     */
    public void restore() {
        this.deletedAt = null;
    }

    /**
     * Check if this statement is deleted
     */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * Check if this statement was created via OCR
     */
    public boolean isFromOcr() {
        return "ocr".equals(source);
    }

    /**
     * Mark this statement as needing review (e.g., low OCR confidence)
     */
    public void markForReview() {
        this.needsReview = true;
    }

    /**
     * Mark this statement as reviewed and approved
     */
    public void approveReview() {
        this.needsReview = false;
    }

    /**
     * Set OCR-related metadata
     */
    public void setOcrMetadata(String requestId, Double confidence, boolean needsReview) {
        this.ocrRequestId = requestId;
        this.ocrConfidence = confidence;
        this.needsReview = needsReview;
        this.source = "ocr";
    }

    @Override
    public String toString() {
        return "Statement{" +
                "id=" + id +
                ", examType='" + examType + '\'' +
                ", title='" + title + '\'' +
                ", variant='" + variant + '\'' +
                ", schoolYearId=" + schoolYearId +
                ", subjectId=" + subjectId +
                ", visible=" + visible +
                ", needsReview=" + needsReview +
                ", source='" + source + '\'' +
                '}';
    }
}
