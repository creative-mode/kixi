package ao.creativemode.kixi.institutions.repository;

import ao.creativemode.kixi.institutions.model.TeachingAssignment;
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
     * The single check behind the statement authorization rule: a teacher may only
     * author statements of a class and subject they were assigned to teach.
     */
    Mono<Boolean> existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
        Long teacherId,
        Long classId,
        Long subjectId,
        Long schoolYearId
    );
}
