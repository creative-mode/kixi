package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.Class;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ClassRepository extends ReactiveCrudRepository<Class, Long> {
    Flux<Class> findAllByDeletedAtIsNull();

    Flux<Class> findAllByDeletedAtIsNotNull();

    Mono<Class> findByIdAndDeletedAtIsNull(Long id);

    Mono<Class> findByIdAndDeletedAtIsNotNull(Long id);

    /**
     * Find a class by grade, course and school year
     */
    Mono<Class> findByGradeAndCourseIdAndSchoolYearIdAndDeletedAtIsNull(
        Integer grade,
        Long courseId,
        Long schoolYearId
    );

    /**
     * Find a class by grade and school year (without course)
     */
    Mono<Class> findByGradeAndSchoolYearIdAndDeletedAtIsNull(
        Integer grade,
        Long schoolYearId
    );

    /**
     * Find classes by grade
     */
    Flux<Class> findByGradeAndDeletedAtIsNull(Integer grade);

    /**
     * Find classes by course
     */
    Flux<Class> findByCourseIdAndDeletedAtIsNull(Long courseId);

    /**
     * Find classes by school year
     */
    Flux<Class> findBySchoolYearIdAndDeletedAtIsNull(Long schoolYearId);
}
