package ao.creativemode.kixi.dto.simulationanswer;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record SimulationAnswerRequest(
    @NotNull(message = "Simulation ID is required") Long simulationId,

    @NotNull(message = "Question ID is required") Long questionId,

    Long selectedOptionId,

    String answerText,

    LocalDateTime answeredAt
) {}
