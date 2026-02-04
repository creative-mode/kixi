package ao.creativemode.kixi.dto.simulation;

import ao.creativemode.kixi.model.SimulationStatus;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record SimulationRequest(
        @NotNull(message = "Account ID is required")
        Long accountId,

        Long statementId,

        Long schoolYearId,

        LocalDateTime startedAt,

        LocalDateTime finishedAt,

        Integer timeSpentSeconds,

        Double finalScore,

        SimulationStatus status
) {}
