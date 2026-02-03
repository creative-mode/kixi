package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.User;
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
}
