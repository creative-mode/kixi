package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.Simulation;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SimulationRepository extends ReactiveCrudRepository<Simulation, Long> {

    Flux<Simulation> findByDeletedAtIsNull();

    Flux<Simulation> findByDeletedAtIsNotNull();

    Mono<Simulation> findByIdAndDeletedAtIsNull(Long id);

    Flux<Simulation> findByIdAndDeletedAtIsNotNull(Long id);
}
