package ao.creativemode.kixi.examrooms.repository;

import ao.creativemode.kixi.examrooms.model.ExamRoomParticipant;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;

public interface ExamRoomParticipantRepository extends ReactiveCrudRepository<ExamRoomParticipant, Long> {
    Mono<ExamRoomParticipant> findByExamRoomIdAndAccountId(Long roomId, Long accountId);
    Flux<ExamRoomParticipant> findByAccountIdOrderByCreatedAtDesc(Long accountId);
    Flux<ExamRoomParticipant> findByExamRoomId(Long roomId);

    @Modifying
    @Query("UPDATE exam_room_participants SET simulation_id = :simulationId, joined_at = CURRENT_TIMESTAMP "
            + "WHERE id = :id AND simulation_id IS NULL")
    Mono<Integer> claimSimulation(Long id, Long simulationId);
}
