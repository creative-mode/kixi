package ao.creativemode.kixi.simulations.dto.simulationanswer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record SimulationAnswerGradeRequest(
    @NotNull(message = "Score is required")
    @PositiveOrZero(message = "Score must be zero or greater")
    Double scoreObtained
) {}
