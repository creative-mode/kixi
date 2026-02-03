package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.Role;
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
}
