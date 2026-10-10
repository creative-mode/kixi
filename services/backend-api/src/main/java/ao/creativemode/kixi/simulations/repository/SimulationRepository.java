package ao.creativemode.kixi.simulations.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.stereotype.Repository;

import ao.creativemode.kixi.simulations.model.Simulation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface SimulationRepository extends ReactiveCrudRepository<Simulation, Long> {
    Flux<Simulation> findByDeletedAtIsNull();
    Flux<Simulation> findByDeletedAtIsNotNull();
    Flux<Simulation> findByAccountIdAndDeletedAtIsNull(Long accountId);
    Mono<Simulation> findByIdAndDeletedAtIsNull(Long id);
    Mono<Simulation> findByIdAndAccountIdAndDeletedAtIsNull(Long id, Long accountId);
    Mono<Simulation> findByIdAndDeletedAtIsNotNull(Long id);

    // @Modifying makes this answer the number of rows updated; without it R2DBC
    // reads the (empty) result set and the Mono completes with nothing.
    @Modifying
    @Query("UPDATE simulations SET status = 'FINISHED' WHERE id = :id AND status = 'IN_PROGRESS' AND deleted_at IS NULL")
    Mono<Integer> claimSubmission(Long id);
}
