package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.Account;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AccountRepository extends ReactiveCrudRepository<Account, Long> {

    Mono<Account> findByIdAndDeletedAtIsNull(Long id);

    Flux<Account> findAllByDeletedAtIsNull();

    Flux<Account> findAllByDeletedAtIsNotNull();

    Mono<Account> findByIdAndDeletedAtIsNotNull(Long id);

    Mono<Account> findByUsernameAndDeletedAtIsNull(String username);

    Mono<Account> findByUsernameAndIdNotAndDeletedAtIsNull(String username, Long id);

    Mono<Account> findByEmailAndDeletedAtIsNull(String email);

    Mono<Account> findByEmailAndIdNotAndDeletedAtIsNull(String email, Long id);

    Flux<Account> findAllByActiveAndDeletedAtIsNull(Boolean active);

    Mono<Long> countByUsernameAndDeletedAtIsNull(String username);

    Mono<Long> countByEmailAndDeletedAtIsNull(String email);
}
