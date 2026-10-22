package ao.creativemode.kixi.simulations.dto.integrity;

import ao.creativemode.kixi.simulations.model.SimulationIntegrityEventKind;
import jakarta.validation.constraints.NotNull;

public record SimulationIntegrityEventRequest(
        @NotNull SimulationIntegrityEventKind kind,
        Long questionId) {
}
