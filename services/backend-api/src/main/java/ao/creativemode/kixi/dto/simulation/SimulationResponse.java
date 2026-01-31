package ao.creativemode.kixi.dto.simulation;

import ao.creativemode.kixi.model.SimulationStatus;
import org.springframework.data.relational.core.mapping.Column;

import java.time.LocalDateTime;

public record SimulationResponse(
         Long id,
         Long accountId,
         Long statementId,
         Long schoolYearId,
         LocalDateTime startedAt,
         LocalDateTime finishedAt,
         Integer timeSpentSeconds,
         Double finalScore,
         SimulationStatus status,
         LocalDateTime createdAt,
         LocalDateTime updatedAt,
         LocalDateTime deletedAt)
{


}
