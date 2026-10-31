package ao.creativemode.kixi.simulations.dto.simulationanswer;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** O que o motor SchoolOps devolve depois de a IA corrigir uma resposta aberta (kixi#542). */
public record SimulationAnswerAiGradeRequest(
    @NotNull(message = "Score is required")
    @PositiveOrZero(message = "Score must be zero or greater")
    Double scoreObtained,

    @NotNull(message = "Feedback is required")
    @Size(max = 4000, message = "Feedback must have at most 4000 characters")
    String feedback,

    @Pattern(regexp = "TOTAL|PARCIAL|MINIMA|NULA", message = "Level must be TOTAL, PARCIAL, MINIMA or NULA")
    String level,

    @DecimalMin(value = "0.0", message = "Confidence must be between 0 and 1")
    @DecimalMax(value = "1.0", message = "Confidence must be between 0 and 1")
    Double confidence
) {}
