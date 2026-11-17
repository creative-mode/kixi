package ao.creativemode.kixi.simulations.dto.simulation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** O motivo por que o motor não conseguiu corrigir as respostas abertas de uma simulação (kixi#609). */
public record CorrectionFailureRequest(@NotBlank @Size(max = 500) String reason) {
}
