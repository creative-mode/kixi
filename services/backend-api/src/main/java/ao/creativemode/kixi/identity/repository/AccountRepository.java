package ao.creativemode.kixi.identity.repository;

import ao.creativemode.kixi.identity.model.Account;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Collection;

public interface AccountRepository extends ReactiveCrudRepository<Account, Long> {

    Mono<Account> findByIdAndDeletedAtIsNull(Long id);

    Flux<Account> findAllByDeletedAtIsNull();

    Flux<Account> findAllByActiveAndDeletedAtIsNull(Boolean active);

    Flux<Account> findAllByDeletedAtIsNotNull();

    Mono<Account> findByIdAndDeletedAtIsNotNull(Long id);

    Mono<Account> findByUsernameAndDeletedAtIsNull(String username);

    Mono<Account> findByEmailAndDeletedAtIsNull(String email);

    Mono<Account> findByUsernameAndIdNotAndDeletedAtIsNull(String username, Long id);

    Flux<Account> findAllByIdInAndDeletedAtIsNull(Collection<Long> ids);

    /** Live accounts holding a role (#147), by its name as stored in {@code roles}. */
    @Query("""
            SELECT a.* FROM accounts a
            WHERE a.deleted_at IS NULL
              AND EXISTS (
                SELECT 1 FROM account_roles ar
                JOIN roles r ON r.id = ar.role_id AND r.deleted_at IS NULL
                WHERE ar.account_id = a.id
                  AND ar.deleted_at IS NULL
                  AND r.name = :role)
            ORDER BY a.id
            """)
    Flux<Account> findAllWithRoleAndDeletedAtIsNull(@Param("role") String role);
}
