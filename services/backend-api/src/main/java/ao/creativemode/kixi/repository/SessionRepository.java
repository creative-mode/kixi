package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.Session;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SessionRepository extends ReactiveCrudRepository<Session, Long> {

    Flux<Session> findAllByDeletedAtIsNull();
    Flux<Session> findAllByDeletedAtIsNotNull();
    Mono<Session> findByIdAndDeletedAtIsNull(Long id);
    Mono<Session> findByIdAndDeletedAtIsNotNull(Long id);
    Flux<Session> findByAccountIdAndDeletedAtIsNull(Long accountId);
    Mono<Long> countByAccountIdAndDeletedAtIsNull(Long accountId);
}
