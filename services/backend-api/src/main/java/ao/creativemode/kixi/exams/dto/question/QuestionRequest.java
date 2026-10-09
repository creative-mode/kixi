package ao.creativemode.kixi.exams.dto.question;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

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
 * <p>{@code maxScore} is required, and this is the one field that could
 * otherwise be left out without breaking the request. The approval gate adds up
 * the scores of the active questions and compares the result with the declared
 * total, and a question with no score at all is skipped by that sum rather than
 * counted as zero. So a PUT that simply left the score out would store NULL,
 * quietly shrink the sum, and make the statement impossible to approve for good
 * — with the edit that did it returning 200. Required here means the caller has
 * to say what it is.
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
    String modelAnswer
) { }