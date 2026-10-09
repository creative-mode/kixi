package ao.creativemode.kixi.exams.dto.question;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * The new order of a statement's questions.
 *
 * <p>Has to name every active question of the statement exactly once. Anything
 * shorter would leave questions on an order nobody chose, and anything longer
 * would name a question that is not there, so both are refused rather than
 * guessed at.
 */
public record QuestionReorderRequest(
        @NotEmpty(message = "The list of question ids is required")
        List<Long> questionIds
) { }