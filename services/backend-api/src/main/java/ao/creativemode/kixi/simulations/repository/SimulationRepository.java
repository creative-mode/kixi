package ao.creativemode.kixi.simulations.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;

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
    Flux<Simulation> findByExamRoomIdAndDeletedAtIsNull(Long examRoomId);

    // The school year is the statement's and, for a statement without one, the room's class's:
    // the leaderboard only counts a simulation of its cohort's year, so one born without it is
    // never ranked, whatever the student scored.
    @Query("INSERT INTO simulations (account_id, statement_id, exam_room_id, exam_room_duration_minutes, started_at, status, school_year_id) "
            + "VALUES (:accountId, :statementId, :roomId, :durationMinutes, :startedAt, 'IN_PROGRESS', "
            + "COALESCE((SELECT st.school_year_id FROM statements st WHERE st.id = :statementId), "
            + "(SELECT c.school_year_id FROM exam_rooms r JOIN classes c ON c.id = r.class_id WHERE r.id = :roomId))) "
            + "ON CONFLICT (exam_room_id, account_id) WHERE exam_room_id IS NOT NULL DO NOTHING RETURNING *")
    Mono<Simulation> insertExamRoomSimulation(Long accountId, Long statementId, Long roomId, Integer durationMinutes,
            LocalDateTime startedAt);
    Mono<Simulation> findByIdAndDeletedAtIsNotNull(Long id);

    /** The simulations the expiration job sweeps on every poll. */
    Flux<Simulation> findByStatusAndDeletedAtIsNull(SimulationStatus status);

    /**
     * Every finished attempt at one statement: the population the community
     * aggregates (issue #119) are computed from.
     */
    Flux<Simulation> findAllByStatementIdAndStatusAndDeletedAtIsNull(
            Long statementId, SimulationStatus status);

    /**
     * The caller's latest finished attempt at one statement, for the
     * "you vs. the average" comparison. Latest, not best: it is the attempt
     * the comparison reads most naturally against.
     */
    Mono<Simulation> findFirstByStatementIdAndAccountIdAndStatusAndDeletedAtIsNullOrderByIdDesc(
            Long statementId, Long accountId, SimulationStatus status);

    /**
     * Serializes answer writes with submission/finalization on PostgreSQL.
     * The lock is held by the surrounding reactive transaction until commit.
     */
    @Query("SELECT * FROM simulations WHERE id = :id AND deleted_at IS NULL FOR UPDATE")
    Mono<Simulation> lockForAnswerWrite(Long id);

    @Query("SELECT * FROM simulations WHERE id = :id AND account_id = :accountId "
            + "AND deleted_at IS NULL FOR UPDATE")
    Mono<Simulation> lockForIntegrityEventWrite(Long id, Long accountId);

    @Query("SELECT s.* FROM simulations s JOIN simulation_answers a ON a.simulation_id = s.id "
            + "WHERE a.id = :answerId AND s.deleted_at IS NULL FOR UPDATE")
    Mono<Simulation> lockForAnswerWriteByAnswerId(Long answerId);

    @Query("SELECT * FROM simulations WHERE exam_room_id = :roomId AND deleted_at IS NULL ORDER BY id FOR UPDATE")
    Flux<Simulation> lockByExamRoomId(Long roomId);

    // @Modifying makes this answer the number of rows updated; without it R2DBC
    // reads the (empty) result set and the Mono completes with nothing.
    @Modifying
    @Query("UPDATE simulations SET status = 'FINISHED' WHERE id = :id AND status = 'IN_PROGRESS' AND deleted_at IS NULL")
    Mono<Integer> claimSubmission(Long id);
}
