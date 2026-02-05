package ao.creativemode.kixi.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Question entity representing a single question within an exam statement.
 *
 * Each question belongs to a Statement and can have multiple QuestionOptions
 * if it's a multiple choice question.
 *
 * Question types:
 * - multiple_choice: Has options with one or more correct answers
 * - short_answer: Expects a brief text/numeric answer
 * - development: Requires an extended written response
 * - true_false: Binary true/false answer
 */
@Table("questions")
public class Question {

    @Id
    private Long id;

    /**
     * Reference to the parent statement
     */
    @Column("statement_id")
    private Long statementId;

    /**
     * Question number within the exam (e.g., 1, 2, 3...)
     */
    @Column("number")
    private Integer number;

    /**
     * The actual question text
     */
    @Column("text")
    private String text;

    /**
     * Type of question: multiple_choice, short_answer, development, true_false
     */
    @Column("question_type")
    private String questionType;

    /**
     * Maximum score/points for this question
     */
    @Column("max_score")
    private Double maxScore;

    /**
     * Order index for display (allows custom ordering independent of question number)
     */
    @Column("order_index")
    private Integer orderIndex;

    /**
     * OCR confidence score for this question (0.0 - 1.0)
     */
    @Column("ocr_confidence")
    private Double ocrConfidence;

    /**
     * Page index where this question was found (for multi-page documents)
     */
    @Column("page_index")
    private Integer pageIndex;

    /**
     * Flag indicating if this question needs human review
     */
    @Column("needs_review")
    private Boolean needsReview;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

    // Constructors

    public Question() {
        this.needsReview = false;
    }

    public Question(Long statementId, Integer number, String text, String questionType) {
        this();
        this.statementId = statementId;
        this.number = number;
        this.text = text;
        this.questionType = questionType;
        this.orderIndex = number;
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getStatementId() {
        return statementId;
    }

    public void setStatementId(Long statementId) {
        this.statementId = statementId;
    }

    public Integer getNumber() {
        return number;
    }

    public void setNumber(Integer number) {
        this.number = number;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getQuestionType() {
        return questionType;
    }

    public void setQuestionType(String questionType) {
        this.questionType = questionType;
    }

    public Double getMaxScore() {
        return maxScore;
    }

    public void setMaxScore(Double maxScore) {
        this.maxScore = maxScore;
    }

    public Integer getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(Integer orderIndex) {
        this.orderIndex = orderIndex;
    }

    public Double getOcrConfidence() {
        return ocrConfidence;
    }

    public void setOcrConfidence(Double ocrConfidence) {
        this.ocrConfidence = ocrConfidence;
    }

    public Integer getPageIndex() {
        return pageIndex;
    }

    public void setPageIndex(Integer pageIndex) {
        this.pageIndex = pageIndex;
    }

    public Boolean getNeedsReview() {
        return needsReview;
    }

    public void setNeedsReview(Boolean needsReview) {
        this.needsReview = needsReview;
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
     * Mark this question as deleted (soft delete)
     */
    public void markAsDeleted() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * Restore a soft-deleted question
     */
    public void restore() {
        this.deletedAt = null;
    }

    /**
     * Check if this question is deleted
     */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * Check if this is a multiple choice question
     */
    public boolean isMultipleChoice() {
        return "multiple_choice".equals(questionType);
    }

    /**
     * Check if this is a short answer question
     */
    public boolean isShortAnswer() {
        return "short_answer".equals(questionType);
    }

    /**
     * Check if this is a development/essay question
     */
    public boolean isDevelopment() {
        return "development".equals(questionType);
    }

    /**
     * Check if this is a true/false question
     */
    public boolean isTrueFalse() {
        return "true_false".equals(questionType);
    }

    /**
     * Mark this question for human review
     */
    public void markForReview() {
        this.needsReview = true;
    }

    /**
     * Mark this question as reviewed and approved
     */
    public void approveReview() {
        this.needsReview = false;
    }

    /**
     * Check if this question has low OCR confidence
     */
    public boolean hasLowConfidence(double threshold) {
        return ocrConfidence != null && ocrConfidence < threshold;
    }

    @Override
    public String toString() {
        return "Question{" +
                "id=" + id +
                ", statementId=" + statementId +
                ", number=" + number +
                ", questionType='" + questionType + '\'' +
                ", maxScore=" + maxScore +
                ", ocrConfidence=" + ocrConfidence +
                ", needsReview=" + needsReview +
                '}';
    }
}
