package ao.creativemode.kixi.training.dto;

import ao.creativemode.kixi.simulations.dto.simulation.SimulationResponse;
import java.util.List;

/** O treino criado e, por tema, quantas questões foram pedidas e quantas havia para dar. */
public record MatrixPracticeResponse(SimulationResponse simulation, List<Allocation> allocation) {

    public record Allocation(Long topicId, Integer requested, Integer provided) {
    }
}
