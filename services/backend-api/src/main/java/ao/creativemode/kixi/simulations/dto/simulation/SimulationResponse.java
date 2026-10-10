package ao.creativemode.kixi.simulations.dto.simulation;

import ao.creativemode.kixi.identity.dto.accounts.AccountBasicResponse;
import ao.creativemode.kixi.academic.dto.schoolyears.SchoolYearResponse;
import ao.creativemode.kixi.exams.dto.statement.StatementBasicResponse;
import ao.creativemode.kixi.simulations.model.SimulationStatus;

import java.time.LocalDateTime;

public record SimulationResponse(
        Long id,
        AccountBasicResponse account,
        StatementBasicResponse statement,
        SchoolYearResponse schoolYear,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        /**
         * Issue #107: the effective instant after which the server stops
         * accepting answers, including accessibility extra time and the
         * configured tolerance. Null when the simulation has no deadline.
         */
        LocalDateTime deadline,
        Integer timeSpentSeconds,
        Double finalScore,
        SimulationStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {
}
