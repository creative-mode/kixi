package ao.creativemode.kixi.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * QuestionOption entity representing an option for a multiple choice question.
 *
 * Each option belongs to a Question and contains the option label (e.g., A, B, C, D),
 * the option text, and whether it is the correct answer.
 */
@Table("question_options")
public class QuestionOption {

    @Id
    private Long id;

    /**
     * Reference to the parent question
     */
    @Column("question_id")
    private Long questionId;

    /**
     * Option label (e.g., "A", "B", "C", "D", or "1", "2", "3", "4")
     */
    @Column("option_label")
    private String optionLabel;

    /**
     * The text content of this option
     */
    @Column("option_text")
    private String optionText;

    /**
     * Whether this option is the correct answer
     */
    @Column("is_correct")
    private Boolean isCorrect;

    /**
     * Order index for display (allows custom ordering)
     */
    @Column("order_index")
    private Integer orderIndex;

    /**
     * OCR confidence score for this option (0.0 - 1.0)
     */
    @Column("ocr_confidence")
    private Double ocrConfidence;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Column("deleted_at")
    private LocalDateTime deletedAt;

    // Constructors

    public QuestionOption() {
        this.isCorrect = false;
    }

    public QuestionOption(Long questionId, String optionLabel, String optionText) {
        this();
        this.questionId = questionId;
        this.optionLabel = optionLabel;
        this.optionText = optionText;
    }

    public QuestionOption(Long questionId, String optionLabel, String optionText, Boolean isCorrect) {
        this(questionId, optionLabel, optionText);
        this.isCorrect = isCorrect;
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public String getOptionLabel() {
        return optionLabel;
    }

    public void setOptionLabel(String optionLabel) {
        this.optionLabel = optionLabel;
    }

    public String getOptionText() {
        return optionText;
    }

    public void setOptionText(String optionText) {
        this.optionText = optionText;
    }

    public Boolean getIsCorrect() {
        return isCorrect;
    }

    public void setIsCorrect(Boolean isCorrect) {
        this.isCorrect = isCorrect;
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
     * Mark this option as deleted (soft delete)
     */
    public void markAsDeleted() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * Restore a soft-deleted option
     */
    public void restore() {
        this.deletedAt = null;
    }

    /**
     * Check if this option is deleted
     */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * Mark this option as the correct answer
     */
    public void markAsCorrect() {
        this.isCorrect = true;
    }

    /**
     * Mark this option as incorrect
     */
    public void markAsIncorrect() {
        this.isCorrect = false;
    }

    /**
     * Check if this option has low OCR confidence
     */
    public boolean hasLowConfidence(double threshold) {
        return ocrConfidence != null && ocrConfidence < threshold;
    }

    /**
     * Get a normalized option label (uppercase, trimmed)
     */
    public String getNormalizedLabel() {
        return optionLabel != null ? optionLabel.toUpperCase().trim() : null;
    }

    @Override
    public String toString() {
        return "QuestionOption{" +
                "id=" + id +
                ", questionId=" + questionId +
                ", optionLabel='" + optionLabel + '\'' +
                ", optionText='" + (optionText != null && optionText.length() > 50
                    ? optionText.substring(0, 50) + "..."
                    : optionText) + '\'' +
                ", isCorrect=" + isCorrect +
                ", ocrConfidence=" + ocrConfidence +
                '}';
    }
}
