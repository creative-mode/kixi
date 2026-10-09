package ao.creativemode.kixi.exams.dto.questionoption;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An option as accepted when creating or editing one.
 *
 * <p>{@code isCorrect} is honoured on both create and edit, and goes through the
 * same rewrite in each, which clears the other options of the question so the
 * answer stays singular. It used to be honoured on create only, and silently
 * dropped on edit: the body asked for the option to become the answer, got a
 * 200, and nothing changed.
 *
 * <p>{@code optionLabel} is not editable. It is the option's identity and is
 * unique per question — a constraint that does not know about removed rows, so a
 * label stays taken once used. {@code PUT .../correct-option} remains the plain
 * way to mark an answer, and needs no label at all.
 */
public record QuestionOptionRequest(
    @NotBlank(message = "The option label is required")
    @Size(max = 10, message = "The option label must not exceed 10 characters")
    String optionLabel,

    @NotBlank(message = "The option text is required")
    String optionText,

    Boolean isCorrect
) { }