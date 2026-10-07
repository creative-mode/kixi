package ao.creativemode.kixi.academic.repository;

import ao.creativemode.kixi.academic.model.Class;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ClassRepository extends ReactiveCrudRepository<Class, Long> {
    Flux<Class> findAllByDeletedAtIsNull();

    Flux<Class> findAllByDeletedAtIsNotNull();

    Mono<Class> findByIdAndDeletedAtIsNull(Long id);

    Mono<Class> findByIdAndDeletedAtIsNotNull(Long id);

    /**
     * Find classes by grade, course and school year. Returns Flux rather than
     * Mono because nothing enforces uniqueness on this combination at the
     * database level, so more than one row can legitimately match; callers
     * that need a single result should take the first element themselves
     * (e.g. via .next()) instead of relying on Mono's at-most-one semantics,
     * which throws IncorrectResultSizeDataAccessException on a second match.
     */
    Flux<Class> findByGradeAndCourseIdAndSchoolYearIdAndDeletedAtIsNull(
        Integer grade,
        Long courseId,
        Long schoolYearId
    );

    /**
     * Find classes by grade and school year (without course). See the
     * Flux note above: this combination is not unique either.
     */
    Flux<Class> findByGradeAndSchoolYearIdAndDeletedAtIsNull(
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
     * Classes of one school, active only. The onboarding picker asks for this after the
     * student chooses the school.
     */
    Flux<Class> findAllByInstitutionIdAndDeletedAtIsNull(Long institutionId);

    /**
     * Classes of one course in one school: the last step of the picker, once the student
     * has chosen both.
     */
    Flux<Class> findAllByCourseIdAndInstitutionIdAndDeletedAtIsNull(Long courseId, Long institutionId);

    /**
     * Find classes by school year
     */
    Flux<Class> findBySchoolYearIdAndDeletedAtIsNull(Long schoolYearId);
}
