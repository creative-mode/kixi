package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.SchoolYear;
import ao.creativemode.kixi.model.SimulationAnswer;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface SimulationAnswerRepository extends ReactiveCrudRepository<SimulationAnswer, Long> {
}
