package ao.creativemode.kixi.academic.repository;

import ao.creativemode.kixi.academic.model.Subject;
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
     * Find subjects by name (case-insensitive). Returns Flux, not Mono:
     * `name` has no unique constraint, so more than one row can match and a
     * Mono-typed derived query would throw IncorrectResultSizeDataAccessException.
     * Callers needing a single result should take the first via .next().
     */
    Flux<Subject> findByNameIgnoreCaseAndDeletedAtIsNull(String name);

    /**
     * Find subjects by name containing (case-insensitive)
     */
    Flux<Subject> findByNameContainingIgnoreCaseAndDeletedAtIsNull(String name);
}
