package ao.creativemode.kixi.academic.repository;

import ao.creativemode.kixi.academic.model.CourseSubject;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CourseSubjectRepository extends ReactiveCrudRepository<CourseSubject, Long> {

    Flux<CourseSubject> findAllByCourseIdAndDeletedAtIsNull(Long courseId);

    Mono<CourseSubject> findByIdAndCourseIdAndDeletedAtIsNull(Long id, Long courseId);

    /** Every active entry of the course for one subject, for any grade. */
    Flux<CourseSubject> findAllByCourseIdAndSubjectIdAndDeletedAtIsNull(Long courseId, Long subjectId);

    /** The entries that cover a class of {@code grade}: all-grades entries and the ones for that grade. */
    @Query("SELECT * FROM course_subjects WHERE course_id = :courseId AND deleted_at IS NULL "
            + "AND (grade IS NULL OR grade = :grade) ORDER BY subject_id ASC")
    Flux<CourseSubject> findApplicable(Long courseId, Integer grade);

    /** Whether the subject is in the curriculum of any active course: what blocks removing a subject. */
    @Query("SELECT COUNT(*) FROM course_subjects cs JOIN courses c ON c.id = cs.course_id AND c.deleted_at IS NULL "
            + "WHERE cs.subject_id = :subjectId AND cs.deleted_at IS NULL")
    Mono<Long> countActiveBySubject(Long subjectId);

    /** The active courses (with their school) where a subject is taught, for the "offerings" view. */
    @Query("SELECT cs.* FROM course_subjects cs JOIN courses c ON c.id = cs.course_id AND c.deleted_at IS NULL "
            + "WHERE cs.subject_id = :subjectId AND cs.deleted_at IS NULL ORDER BY c.institution_id, c.code, cs.grade")
    Flux<CourseSubject> findActiveBySubject(Long subjectId);

    /** The curriculum of many courses in one query, for the structure of a school. */
    @Query("SELECT * FROM course_subjects WHERE course_id IN (:courseIds) AND deleted_at IS NULL ORDER BY course_id, subject_id, grade")
    Flux<CourseSubject> findAllActiveByCourseIds(java.util.Collection<Long> courseIds);
}
