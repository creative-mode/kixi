package ao.creativemode.kixi.simulations.repository;

import java.time.OffsetDateTime;
import java.util.Collection;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.stereotype.Repository;

import ao.creativemode.kixi.simulations.model.SimulationIntegrityEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface SimulationIntegrityEventRepository extends ReactiveCrudRepository<SimulationIntegrityEvent, Long> {
    @Query("""
            SELECT simulation_id, id, kind, question_id, occurred_at
            FROM (
                SELECT e.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY e.simulation_id
                           ORDER BY e.occurred_at DESC, e.id DESC
                       ) AS event_rank
                FROM simulation_integrity_events e
                WHERE e.simulation_id IN (:simulationIds)
            ) recent
            WHERE event_rank <= :perSimulationLimit
            ORDER BY simulation_id, occurred_at ASC, id ASC
            """)
    Flux<SimulationIntegrityEvent> findRecentBySimulationIdIn(
            Collection<Long> simulationIds, int perSimulationLimit);

    @Query("SELECT COUNT(*) FROM simulation_integrity_events WHERE simulation_id = :simulationId AND occurred_at > :since")
    Mono<Long> countBySimulationIdAndAtAfter(Long simulationId, OffsetDateTime since);
}
