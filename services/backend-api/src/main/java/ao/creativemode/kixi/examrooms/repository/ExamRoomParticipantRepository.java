package ao.creativemode.kixi.examrooms.repository;

import ao.creativemode.kixi.examrooms.model.ExamRoomParticipant;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ExamRoomParticipantRepository extends ReactiveCrudRepository<ExamRoomParticipant, Long> {
    Mono<ExamRoomParticipant> findByExamRoomIdAndAccountId(Long roomId, Long accountId);
    Flux<ExamRoomParticipant> findByAccountIdOrderByCreatedAtDesc(Long accountId);
    Flux<ExamRoomParticipant> findByExamRoomId(Long roomId);
}
