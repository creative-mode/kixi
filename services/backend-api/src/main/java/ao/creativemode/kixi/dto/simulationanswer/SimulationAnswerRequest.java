package ao.creativemode.kixi.dto.simulationanswer;

import java.time.LocalDateTime;

public record SimulationAnswerRequest(

        Long simulationId,
        Long questionId,
        Long selectedOptionId,
        String textAnswer,
        LocalDateTime answeredAt
) { }
