package ao.creativemode.kixi.dto.simulation;

import ao.creativemode.kixi.identity.dto.accounts.AccountBasicResponse;
import ao.creativemode.kixi.academic.dto.schoolyears.SchoolYearResponse;
import ao.creativemode.kixi.dto.statement.StatementBasicResponse;
import ao.creativemode.kixi.model.SimulationStatus;

import java.time.LocalDateTime;

public record SimulationResponse(
        Long id,
        AccountBasicResponse account,
        StatementBasicResponse statement,
        SchoolYearResponse schoolYear,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Integer timeSpentSeconds,
        Double finalScore,
        SimulationStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {
}
