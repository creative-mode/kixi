package ao.creativemode.kixi.identity.repository;

import ao.creativemode.kixi.identity.model.Session;
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

    /**
     * Hard delete every session of an account, active or trashed. Sessions are
     * account-scoped and must be removed before the account is purged.
     */
    Mono<Void> deleteAllByAccountId(Long accountId);
}
