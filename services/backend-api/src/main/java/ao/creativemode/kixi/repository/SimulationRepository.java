package ao.creativemode.kixi.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

import ao.creativemode.kixi.model.Simulation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface SimulationRepository extends ReactiveCrudRepository<Simulation, Long> {
    Flux<Simulation> findByDeletedAtIsNull();
    Flux<Simulation> findByDeletedAtIsNotNull();
    Mono<Simulation> findByIdAndDeletedAtIsNull(Long id);
    Mono<Simulation> findByIdAndDeletedAtIsNotNull(Long id);
}
