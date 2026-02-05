package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.Subject;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SubjectRepository
    extends ReactiveCrudRepository<Subject, Long>
{
    Flux<Subject> findAllByDeletedAtIsNull();
    Flux<Subject> findAllByDeletedAtIsNotNull();
    Mono<Subject> findByIdAndDeletedAtIsNull(Long id);
    Mono<Subject> findByIdAndDeletedAtIsNotNull(Long id);
    Mono<Subject> findByCodeAndDeletedAtIsNull(String code);
    Mono<Subject> findByCodeAndDeletedAtIsNotNull(String code);

    /**
     * Find a subject by name (case-insensitive)
     */
    Mono<Subject> findByNameIgnoreCaseAndDeletedAtIsNull(String name);

    /**
     * Find subjects by name containing (case-insensitive)
     */
    Flux<Subject> findByNameContainingIgnoreCaseAndDeletedAtIsNull(String name);
}
