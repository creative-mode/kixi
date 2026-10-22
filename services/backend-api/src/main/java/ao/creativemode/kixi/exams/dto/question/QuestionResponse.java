package ao.creativemode.kixi.exams.dto.question;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A question as read back.
 *
 * <p>{@code modelAnswer} and {@code needsReview} are the answer key, and they are
 * only filled in for an account that may write the statement. A student reading
 * this route gets them as null, and {@code NON_NULL} keeps them out of the JSON
 * altogether rather than shipping a field that is quietly always empty: that way
 * a client is never left deciding what a missing key meant.
 *
 * <p>The same omission applies to a staff answer when a question genuinely has
 * no model answer. That is the trade for not sending a field whose emptiness
 * carries no information either way.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuestionResponse(
        Long id,
        Long statementId,
        Integer number,
        String text,
        String questionType,
        Double maxScore,
        Integer orderIndex,
        Integer pageIndex,
        String modelAnswer,
        Boolean needsReview,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt,
        Long parentId,
        String label,
        String groupLayout,
        String rawText,
        Double ocrConfidence,
        Boolean ocrReviewed,
        List<FormulaEvidence> formulas
) {
    public QuestionResponse(Long id, Long statementId, Integer number, String text, String questionType,
            Double maxScore, Integer orderIndex, Integer pageIndex, String modelAnswer, Boolean needsReview,
            LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime deletedAt, Long parentId, String label) {
        this(id, statementId, number, text, questionType, maxScore, orderIndex, pageIndex, modelAnswer,
            needsReview, createdAt, updatedAt, deletedAt, parentId, label, null, null, null, null, null);
    }

    public QuestionResponse(Long id, Long statementId, Integer number, String text, String questionType,
            Double maxScore, Integer orderIndex, Integer pageIndex, String modelAnswer, Boolean needsReview,
            LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime deletedAt, Long parentId, String label,
            String rawText, Double ocrConfidence, Boolean ocrReviewed, List<FormulaEvidence> formulas) {
        this(id, statementId, number, text, questionType, maxScore, orderIndex, pageIndex, modelAnswer,
            needsReview, createdAt, updatedAt, deletedAt, parentId, label, null, rawText, ocrConfidence,
            ocrReviewed, formulas);
    }

    public record FormulaEvidence(String raw, String latex, Double confidence, String kind,
            Double ocrConfidence, Double layoutConfidence) {
        public FormulaEvidence(String raw, String latex, Double confidence, String kind) {
            this(raw, latex, confidence, kind, null, null);
        }
    }
}
