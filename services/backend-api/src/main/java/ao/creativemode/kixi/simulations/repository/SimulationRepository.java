package ao.creativemode.kixi.simulations.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.stereotype.Repository;

import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface SimulationRepository extends ReactiveCrudRepository<Simulation, Long> {
    Flux<Simulation> findByDeletedAtIsNull();
    Flux<Simulation> findByDeletedAtIsNotNull();
    Flux<Simulation> findByAccountIdAndDeletedAtIsNull(Long accountId);
    Flux<Simulation> findByAccountId(Long accountId);
    Mono<Simulation> findByIdAndDeletedAtIsNull(Long id);
    Mono<Simulation> findByIdAndAccountIdAndDeletedAtIsNull(Long id, Long accountId);
    Mono<Simulation> findByIdAndAccountId(Long id, Long accountId);
    Mono<Simulation> findByExamRoomIdAndAccountIdAndDeletedAtIsNull(Long examRoomId, Long accountId);

    @Query("INSERT INTO simulations (account_id, statement_id, exam_room_id, exam_room_duration_minutes, started_at, status) "
            + "VALUES (:accountId, :statementId, :roomId, :durationMinutes, CURRENT_TIMESTAMP, 'IN_PROGRESS') "
            + "ON CONFLICT (exam_room_id, account_id) WHERE exam_room_id IS NOT NULL DO NOTHING RETURNING *")
    Mono<Simulation> insertExamRoomSimulation(Long accountId, Long statementId, Long roomId, Integer durationMinutes);
    Mono<Simulation> findByIdAndDeletedAtIsNotNull(Long id);

    /** The simulations the expiration job sweeps on every poll. */
    Flux<Simulation> findByStatusAndDeletedAtIsNull(SimulationStatus status);

    /**
     * Serializes answer writes with submission/finalization on PostgreSQL.
     * The lock is held by the surrounding reactive transaction until commit.
     */
    @Query("SELECT * FROM simulations WHERE id = :id AND deleted_at IS NULL FOR UPDATE")
    Mono<Simulation> lockForAnswerWrite(Long id);

    // @Modifying makes this answer the number of rows updated; without it R2DBC
    // reads the (empty) result set and the Mono completes with nothing.
    @Modifying
    @Query("UPDATE simulations SET status = 'FINISHED' WHERE id = :id AND status = 'IN_PROGRESS' AND deleted_at IS NULL")
    Mono<Integer> claimSubmission(Long id);
}
