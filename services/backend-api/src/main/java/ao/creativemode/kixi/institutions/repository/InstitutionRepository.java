package ao.creativemode.kixi.institutions.repository;

import ao.creativemode.kixi.institutions.model.Institution;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface InstitutionRepository extends ReactiveCrudRepository<Institution, Long> {

    Flux<Institution> findAllByDeletedAtIsNull();
    Flux<Institution> findAllByDeletedAtIsNotNull();
    Mono<Institution> findByIdAndDeletedAtIsNull(Long id);
    Mono<Institution> findByIdAndDeletedAtIsNotNull(Long id);
}
