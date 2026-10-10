package ao.creativemode.kixi.examrooms.repository;

import ao.creativemode.kixi.examrooms.model.ExamRoom;
import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ExamRoomRepository extends ReactiveCrudRepository<ExamRoom, Long> {
    Flux<ExamRoom> findByTeacherAccountIdOrderByCreatedAtDesc(Long teacherAccountId);
    Mono<ExamRoom> findByIdAndTeacherAccountId(Long id, Long teacherAccountId);
    @Query("SELECT * FROM exam_rooms WHERE id = :id FOR UPDATE")
    Mono<ExamRoom> lockForUpdate(Long id);
    @Modifying
    @Query("UPDATE exam_rooms SET status = :next, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND status = :current")
    Mono<Integer> transition(Long id, ExamRoomStatus current, ExamRoomStatus next);
}
