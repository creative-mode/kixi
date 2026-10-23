package ao.creativemode.kixi.simulations.dto.integrity;

import java.time.LocalDateTime;

import ao.creativemode.kixi.simulations.model.SimulationIntegrityEventKind;

public record SimulationIntegrityEventResponse(
        Long id,
        Long simulationId,
        SimulationIntegrityEventKind kind,
        LocalDateTime at,
        Long questionId) {
}
