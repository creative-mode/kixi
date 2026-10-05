package ao.creativemode.kixi.identity.repository;

import ao.creativemode.kixi.identity.model.Teacher;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TeacherRepository extends ReactiveCrudRepository<Teacher, Long> {

    Flux<Teacher> findAllByDeletedAtIsNull();
    Flux<Teacher> findAllByDeletedAtIsNotNull();
    Mono<Teacher> findByIdAndDeletedAtIsNull(Long id);
    Mono<Teacher> findByIdAndDeletedAtIsNotNull(Long id);

    /** The active teacher profile behind an account, if any. */
    Mono<Teacher> findByAccountIdAndDeletedAtIsNull(Long accountId);
}
