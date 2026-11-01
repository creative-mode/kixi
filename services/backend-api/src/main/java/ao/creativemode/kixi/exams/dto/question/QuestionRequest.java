package ao.creativemode.kixi.exams.dto.question;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

/**
 * A question as accepted when creating or editing one.
 *
 * <p>{@code number} and {@code orderIndex} are not accepted: they are assigned
 * from what the statement already holds, so that a number is never handed out
 * twice. questions.number is unique per statement and a soft-deleted question
 * keeps its number for good.
 *
 * <p>{@code questionType} is free text because the vocabulary is not settled:
 * the OCR paths write {@code development} for a dissertative question and the
 * exam builder writes {@code open}. Only {@code multiple_choice} has a meaning
 * to the approval gate, which is why it is the one value that matters here.
 *
 * <p>{@code maxScore} is required until the score and approval rule is settled.
 * The approval gate skips null scores when summing active questions, so accepting
 * one here could allow an unscored question to pass approval.
 *
 * <p>{@code questionType} and {@code pageIndex} are optional and, when absent,
 * are left as they are: neither takes part in the approval gate, so a partial
 * edit cannot strand a statement.
 */
public record QuestionRequest(
    @NotBlank(message = "The question text is required")
    String text,

    @Size(max = 50, message = "The question type must not exceed 50 characters")
    String questionType,

    @NotNull(message = "The question score is required")
    @PositiveOrZero(message = "The score cannot be negative")
    @Digits(integer = 8, fraction = 2,
        message = "The score must be at most 99999999.99")
    Double maxScore,

    @PositiveOrZero(message = "The page index cannot be negative")
    Integer pageIndex,

    // No size limit: questions.model_answer is TEXT.
    String modelAnswer,

    /** Explicit human acknowledgement of OCR text/formula evidence. */
    Boolean ocrReviewed,
    /** Parent group for a new alínea; immutable when updating an existing question. */
    Long parentId,
    @Size(max = 10, message = "The item label must not exceed 10 characters")
    String label,
    @Pattern(regexp = "lettered|brace", message = "The group layout must be lettered or brace")
    String groupLayout
) {
    public QuestionRequest(String text, String questionType, Double maxScore, Integer pageIndex, String modelAnswer) {
        this(text, questionType, maxScore, pageIndex, modelAnswer, null, null, null, null);
    }

    public QuestionRequest(String text, String questionType, Double maxScore, Integer pageIndex, String modelAnswer,
            Boolean ocrReviewed) {
        this(text, questionType, maxScore, pageIndex, modelAnswer, ocrReviewed, null, null, null);
    }
}
