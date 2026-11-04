package ao.creativemode.kixi.identity.repository;

import ao.creativemode.kixi.identity.model.Role;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RoleRepository extends ReactiveCrudRepository<Role, Long> {

    Flux<Role> findAllByDeletedAtIsNull();
    Flux<Role> findAllByDeletedAtIsNotNull();
    Mono<Role> findByIdAndDeletedAtIsNull(Long id);
    Mono<Role> findByIdAndDeletedAtIsNotNull(Long id);
    Mono<Role> findByNameAndDeletedAtIsNull(String name);
    Mono<Role> findByNameAndIdNotAndDeletedAtIsNull(String name, Long id);

    /** The names of the active roles of an account in one query, in the order they were granted (kixi#566). */
    @Query("""
            SELECT r.name FROM account_roles ar
            JOIN roles r ON r.id = ar.role_id
            WHERE ar.account_id = :accountId AND ar.deleted_at IS NULL AND r.deleted_at IS NULL
            ORDER BY ar.id
            """)
    Flux<String> findActiveNamesByAccountId(@Param("accountId") Long accountId);
}
