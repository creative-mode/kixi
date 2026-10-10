package ao.creativemode.kixi.simulations.dto.simulationanswer;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SimulationAnswerResponse(
    Long id,
    Long simulationId,
    Long questionId,
    Long selectedOptionId,
    String answerText,
    Float scoreObtained,
    Boolean isCorrect,
    ao.creativemode.kixi.simulations.model.SimulationAnswerStatus reviewStatus,
    LocalDateTime answeredAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime deletedAt
) {}
