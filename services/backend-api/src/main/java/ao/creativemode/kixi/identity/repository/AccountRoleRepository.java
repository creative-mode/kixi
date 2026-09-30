package ao.creativemode.kixi.identity.repository;

import ao.creativemode.kixi.identity.model.AccountRole;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AccountRoleRepository extends ReactiveCrudRepository<AccountRole, Long> {

    Flux<AccountRole> findByAccountIdAndDeletedAtIsNull(Long accountId);

    Flux<AccountRole> findByRoleIdAndDeletedAtIsNull(Long roleId);

    Flux<AccountRole> findByAccountIdAndDeletedAtIsNotNull(Long accountId);

    Flux<AccountRole> findByRoleIdAndDeletedAtIsNotNull(Long roleId);

    Mono<AccountRole> findByAccountIdAndRoleIdAndDeletedAtIsNull(Long accountId, Long roleId);

    Mono<AccountRole> findFirstByAccountIdAndRoleId(Long accountId, Long roleId);

    Mono<Boolean> existsByAccountIdAndRoleIdAndDeletedAtIsNull(Long accountId, Long roleId);

    /**
     * Hard delete every role association of an account, active or trashed.
     * Required before purging the account itself: a trashed association still
     * holds the account foreign key, so a soft delete alone does not unblock it.
     */
    Mono<Void> deleteAllByAccountId(Long accountId);

    /**
     * Hard delete every role association of a role, active or trashed.
     */
    Mono<Void> deleteAllByRoleId(Long roleId);
}
