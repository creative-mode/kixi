package ao.creativemode.kixi.dto.simulation;

import ao.creativemode.kixi.model.SimulationStatus;

import java.time.LocalDateTime;

public record SimulationUpdateRequest(
        SimulationStatus status,
        LocalDateTime finishedAt,
        Integer timeSpentSeconds,
        Double finalScore

) {
}
