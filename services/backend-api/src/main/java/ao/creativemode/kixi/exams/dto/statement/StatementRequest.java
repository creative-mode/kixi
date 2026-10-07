package ao.creativemode.kixi.exams.dto.statement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * The metadata of a statement, as accepted when editing one.
 *
 * <p>Deliberately without questions: an edit corrects the metadata of a paper
 * and must leave the questions alone. That matters most for a statement the OCR
 * produced, whose questions and scores came out of a scan and are the reason the
 * teacher is editing it in the first place.
 *
 * <p>Visibility is absent for the same reason in reverse: publishing is what
 * {@code POST /statements/{id}/approve} and {@code PATCH /statements/{id}/visibility}
 * are for, and a statement the OCR flagged for review should not be able to skip
 * that gate as a side effect of fixing its metadata.
 *
 * <p>The required fields mirror {@link ManualStatementRequest} so that both
 * routes describe a statement the same way; the class stays optional because a
 * school-wide statement with no class is the administrator's to write.
 */
public record StatementRequest(
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

    @PositiveOrZero(message = "The total score cannot be negative")
    Double totalMaxScore,

    Long schoolYearId,

    Long termId,

    Long classId,

    Long courseId
) { }
