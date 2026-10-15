package ao.creativemode.kixi.institutions.repository;

import ao.creativemode.kixi.institutions.model.TeachingAssignment;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TeachingAssignmentRepository extends ReactiveCrudRepository<TeachingAssignment, Long> {

    Flux<TeachingAssignment> findAllByDeletedAtIsNull();

    Flux<TeachingAssignment> findAllByDeletedAtIsNotNull();

    Mono<TeachingAssignment> findByIdAndDeletedAtIsNull(Long id);

    Mono<TeachingAssignment> findByIdAndDeletedAtIsNotNull(Long id);

    /** The classes and subjects a teacher is responsible for; the payload of GET /me. */
    Flux<TeachingAssignment> findAllByTeacherIdAndDeletedAtIsNull(Long teacherId);

    /** Any state (active or trashed), so a removed assignment is restored instead of duplicated. */
    Mono<TeachingAssignment> findFirstByTeacherIdAndClassIdAndSubjectIdAndSchoolYearId(
        Long teacherId,
        Long classId,
        Long subjectId,
        Long schoolYearId
    );

    /**
     * The active assignment the statement's author holds for that class and subject,
     * for the tutor style of issue #116.
     *
     * <p>{@code statements.created_by} is an <b>account</b> id, while
     * {@code teaching_assignments.teacher_id} points at {@code teachers.id}: the two
     * only meet through {@code teachers.account_id}. The school year is the class's,
     * as in {@code TeachingAssignmentService.requireTeaches}, so a statement without
     * its own year still finds the assignment.</p>
     *
     * <p>Distinct from {@link #findFirstByTeacherIdAndClassIdAndSubjectIdAndSchoolYearId},
     * which looks at trashed rows on purpose so a removed assignment can be restored
     * rather than duplicated. A withdrawn assignment has withdrawn its tutor style with
     * it, so this one filters them out.</p>
     */
    @Query("""
        SELECT ta.* FROM teaching_assignments ta
          JOIN teachers t ON t.id = ta.teacher_id AND t.deleted_at IS NULL
          JOIN classes c ON c.id = ta.class_id AND c.school_year_id = ta.school_year_id
         WHERE t.account_id = :authorAccountId
           AND ta.class_id = :classId
           AND ta.subject_id = :subjectId
           AND ta.deleted_at IS NULL
         LIMIT 1
        """)
    Mono<TeachingAssignment> findActiveForAuthor(
        @Param("authorAccountId") Long authorAccountId,
        @Param("classId") Long classId,
        @Param("subjectId") Long subjectId
    );

    /**
     * The single check behind the statement authorization rule: a teacher may only
     * author statements of a class and subject they were assigned to teach.
     */
    Mono<Boolean> existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
        Long teacherId,
        Long classId,
        Long subjectId,
        Long schoolYearId
    );

    /** Active assignments in any of the classes, for the read models that group by class (#253). */
    @Query("SELECT * FROM teaching_assignments WHERE class_id IN (:classIds) AND deleted_at IS NULL")
    Flux<TeachingAssignment> findAllActiveByClassIds(java.util.Collection<Long> classIds);
}
