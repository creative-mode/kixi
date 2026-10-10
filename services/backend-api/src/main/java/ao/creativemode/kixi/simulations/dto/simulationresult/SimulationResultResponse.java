package ao.creativemode.kixi.simulations.dto.simulationresult;

import ao.creativemode.kixi.simulations.model.SimulationStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The outcome of a finished simulation, with the answer key.
 *
 * <p>This is the only route that shows a student which option was right, and the
 * only one that shows the model answer. Before a simulation reaches
 * {@link SimulationStatus#FINISHED} it does not exist: a result is something that
 * happened, not something you can read while it is still happening.
 *
 * <p>The shape is deliberately flat — every question carries its options — so a
 * client renders the corrected paper from one response instead of walking back to
 * {@code /statements/{id}/full} and joining by hand.
 */
public record SimulationResultResponse(
        Long simulationId,
        SimulationStatus status,
        Double finalScore,
        Integer correctAnswers,
        Integer totalQuestions,
        Integer pendingReviewCount,
        Integer timeSpentSeconds,
        LocalDateTime finishedAt,
        List<QuestionResult> questions
) {

    /**
     * One question, the answer for it, and what the student did.
     *
     * @param selectedOptionId what the student chose, null if the answer was free
     *                         text or the question was never reached
     * @param answerText       what the student wrote, when the question was not
     *                         answered by choosing an option
     * @param correctOptionId  the answer key; null when the question has no
     *                         option marked correct, which the approval gate
     *                         permits for an open question
     */
    public record QuestionResult(
            Long questionId,
            Integer number,
            String text,
            String questionType,
            Double maxScore,
            String modelAnswer,
            Long correctOptionId,
            Long selectedOptionId,
            String answerText,
            Float scoreObtained,
            Boolean isCorrect,
            ao.creativemode.kixi.simulations.model.SimulationAnswerStatus reviewStatus,
            List<OptionResult> options
    ) { }

    public record OptionResult(
            Long id,
            String optionLabel,
            String optionText,
            Boolean isCorrect
    ) { }
}
