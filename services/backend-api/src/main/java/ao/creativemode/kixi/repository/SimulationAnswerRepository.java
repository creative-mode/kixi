package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.SchoolYear;
import ao.creativemode.kixi.model.SimulationAnswer;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SimulationAnswerRepository extends ReactiveCrudRepository<SimulationAnswer, Long> {


    Flux<SimulationAnswer> findAllByDeletedAtIsNull();
    Flux<SimulationAnswer> findAllByDeletedAtIsNotNull();
    Mono<SimulationAnswer> findByIdAndDeletedAtIsNull(Long id);
    Mono<SimulationAnswer> findByIdAndDeletedAtIsNotNull(Long id);



}
