package ao.creativemode.kixi.exams.dto.statement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A statement written by hand in the exam builder: it belongs to an
 * institution and to one of that institution's subjects.
 */
public record ManualStatementRequest(
    @NotNull(message = "The institution is required")
    Long institutionId,

    @NotNull(message = "The subject is required")
    Long subjectId,

    @NotBlank(message = "The title is required")
    @Size(min = 3, max = 255, message = "The title must be between 3 and 255 characters")
    String title,

    @NotBlank(message = "The exam type is required")
    @Size(max = 100, message = "The exam type must not exceed 100 characters")
    String examType,

    @Positive(message = "The duration must be greater than zero")
    Integer durationMinutes,

    @Size(max = 50, message = "The variant must not exceed 50 characters")
    String variant,

    @Size(max = 5000, message = "The instructions must not exceed 5000 characters")
    String instructions,

    Long schoolYearId,
    Long termId,
    Long classId,
    Long courseId,
    Boolean visible,

    @NotEmpty(message = "A statement needs at least one question")
    @Valid
    List<Question> questions
) {
    public record Question(
        @NotBlank(message = "The question text is required")
        String text,

        @PositiveOrZero(message = "The score cannot be negative")
        @Digits(integer = 8, fraction = 2,
            message = "The score must be at most 99999999.99")
        Double maxScore,

        @Valid
        List<Option> options,
        /**
         * {@code open}, {@code multiple_choice}, {@code true_false} or {@code group}. Left out, it is
         * derived as before: {@code multiple_choice} when there are options, {@code open} otherwise.
         */
        @Size(max = 20, message = "The question type must not exceed 20 characters")
        String type,
        /** The alíneas of a {@code group} question. Only a group has them. */
        @Valid
        List<Item> items
    ) {
        /** The shape the exam builder sent before types and alíneas existed. */
        public Question(String text, Double maxScore, List<Option> options) {
            this(text, maxScore, options, null, null);
        }
    }

    /**
     * One alínea of a group: its own type, score and options. Never a group itself.
     * {@code label} is optional and defaults to a, b, c... in order.
     */
    public record Item(
        @Size(max = 10, message = "The item label must not exceed 10 characters")
        String label,
        @NotBlank(message = "The item text is required")
        String text,
        @Size(max = 20, message = "The question type must not exceed 20 characters")
        String type,
        @PositiveOrZero(message = "The score cannot be negative")
        @Digits(integer = 8, fraction = 2,
            message = "The score must be at most 99999999.99")
        Double maxScore,
        @Valid
        List<Option> options
    ) {}

    public record Option(
        @NotBlank(message = "The option label is required")
        @Size(max = 10, message = "The option label must not exceed 10 characters")
        String label,

        @NotBlank(message = "The option text is required")
        String text,

        Boolean correct
    ) {}
}
