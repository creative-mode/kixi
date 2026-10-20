package ao.creativemode.kixi.simulations.repository;

import java.time.LocalDateTime;
import java.util.Collection;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

import ao.creativemode.kixi.simulations.model.SimulationIntegrityEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface SimulationIntegrityEventRepository extends ReactiveCrudRepository<SimulationIntegrityEvent, Long> {
    Flux<SimulationIntegrityEvent> findBySimulationIdInOrderByAtAscIdAsc(Collection<Long> simulationIds);

    Mono<Long> countBySimulationIdAndAtAfter(Long simulationId, LocalDateTime since);
}
