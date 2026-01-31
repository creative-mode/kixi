package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.AccountRole;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AccountRoleRepository extends ReactiveCrudRepository<AccountRole, Long> {

    Flux<AccountRole> findByAccountIdAndDeletedAtIsNull(Long accountId);

    Flux<AccountRole> findByRoleIdAndDeletedAtIsNull(Long roleId);

    Flux<AccountRole> findByAccountIdAndDeletedAtIsNotNull(Long accountId);

    Flux<AccountRole> findByRoleIdAndDeletedAtIsNotNull(Long roleId);

    Mono<AccountRole> findByAccountIdAndRoleIdAndDeletedAtIsNull(Long accountId, Long roleId);

    Mono<Boolean> existsByAccountIdAndRoleIdAndDeletedAtIsNull(Long accountId, Long roleId);
}
