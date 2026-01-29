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
}
