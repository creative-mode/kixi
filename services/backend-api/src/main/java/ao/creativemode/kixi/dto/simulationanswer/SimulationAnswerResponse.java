package ao.creativemode.kixi.dto.simulationanswer;

import java.time.LocalDateTime;

public record SimulationAnswerResponse(

         Long id,
         Long simulationId,
         Long questionId,
         Long selectedOptionId,
         float scoreObtained,
         Boolean isCorrect,
         LocalDateTime answeredAt,
         LocalDateTime createdAt,
         LocalDateTime updatedAt,
         LocalDateTime deletedAt

) { }
