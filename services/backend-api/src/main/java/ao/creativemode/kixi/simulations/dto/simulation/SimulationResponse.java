package ao.creativemode.kixi.simulations.dto.simulation;

import ao.creativemode.kixi.identity.dto.accounts.AccountBasicResponse;
import ao.creativemode.kixi.academic.dto.schoolyears.SchoolYearResponse;
import ao.creativemode.kixi.exams.dto.statement.StatementBasicResponse;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SimulationResponse(
        Long id,
        AccountBasicResponse account,
        StatementBasicResponse statement,
        SchoolYearResponse schoolYear,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        /**
         * Issue #107: the scheduled effective instant calculated from duration,
         * accessibility extra time, and tolerance. Room closure is an internal
         * rejection signal and never changes this public, serializable value.
         * Null when the simulation has no deadline.
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
