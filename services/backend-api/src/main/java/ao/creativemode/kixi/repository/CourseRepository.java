package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.Course;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CourseRepository extends ReactiveCrudRepository<Course, Long> {
    Mono<Course> findByIdAndDeletedAtIsNull(Long id);

    Flux<Course> findAllByDeletedAtIsNull();

    Flux<Course> findAllByDeletedAtIsNotNull();

    Mono<Course> findByIdAndDeletedAtIsNotNull(Long id);

    Mono<Course> findByCodeAndDeletedAtIsNull(String code);

    Mono<Course> findByCodeAndIdNotAndDeletedAtIsNull(String code, Long id);

    /**
     * Find courses by name (case-insensitive). Returns Flux, not Mono:
     * `name` has no unique constraint, so more than one row can match and a
     * Mono-typed derived query would throw IncorrectResultSizeDataAccessException.
     * Callers needing a single result should take the first via .next().
     */
    Flux<Course> findByNameIgnoreCaseAndDeletedAtIsNull(String name);

    /**
     * Find courses by name containing (case-insensitive)
     */
    Flux<Course> findByNameContainingIgnoreCaseAndDeletedAtIsNull(String name);
}
