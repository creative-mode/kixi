package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.SchoolYear;
import ao.creativemode.kixi.model.SimulationAnswer;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface SimulationAnswerRepository extends ReactiveCrudRepository<SimulationAnswer, Long> {


    Flux<SimulationAnswer> findAllByDeletedAtIsNull();
    Flux<SimulationAnswer> findAllByDeletedAtIsNotNull();
    Flux<SimulationAnswer> findByIdAndDeletedAtIsNull(Long id);
    Flux<SimulationAnswer> findByIdAndDeletedAtIsNotNull(Long id);



}
