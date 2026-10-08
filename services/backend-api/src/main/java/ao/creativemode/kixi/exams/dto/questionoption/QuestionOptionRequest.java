package ao.creativemode.kixi.exams.dto.questionoption;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An option as accepted when creating or editing one.
 *
 * <p>{@code isCorrect} may be set on creation. Marking an answer on an existing
 * option is {@code PUT .../correct-option}, which clears the others in the same
 * question, so setting it here is only honoured for the options this request
 * creates.
 */
public record QuestionOptionRequest(
    @NotBlank(message = "The option label is required")
    @Size(max = 10, message = "The option label must not exceed 10 characters")
    String optionLabel,

    @NotBlank(message = "The option text is required")
    String optionText,

    Boolean isCorrect
) { }