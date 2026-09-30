package ao.creativemode.kixi.identity.repository;

import ao.creativemode.kixi.identity.model.User;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserRepository extends ReactiveCrudRepository<User, Long> {

    Flux<User> findAllByDeletedAtIsNull();
    Flux<User> findAllByDeletedAtIsNotNull();
    Mono<User> findByIdAndDeletedAtIsNull(Long id);
    Mono<User> findByIdAndDeletedAtIsNotNull(Long id);
    Flux<User> findByAccountIdAndDeletedAtIsNull(Long accountId);
    Mono<Long> countByAccountIdAndDeletedAtIsNull(Long accountId);

    /**
     * Hard delete every profile bound to an account. A profile row holds the
     * account foreign key, so it must be removed before the account is purged.
     */
    Mono<Void> deleteAllByAccountId(Long accountId);
}
