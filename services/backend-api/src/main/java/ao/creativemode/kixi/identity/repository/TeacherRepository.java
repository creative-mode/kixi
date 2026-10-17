package ao.creativemode.kixi.identity.repository;

import ao.creativemode.kixi.identity.model.Teacher;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TeacherRepository extends ReactiveCrudRepository<Teacher, Long> {

    Flux<Teacher> findAllByDeletedAtIsNull();
    Flux<Teacher> findAllByDeletedAtIsNotNull();
    Mono<Teacher> findByIdAndDeletedAtIsNull(Long id);
    Mono<Teacher> findByIdAndDeletedAtIsNotNull(Long id);

    /** The active teachers affiliated with a school, for its public profile (issue #331). */
    @Query("""
        SELECT t.* FROM teachers t
        JOIN institution_teachers it ON it.teacher_id = t.id
        WHERE it.institution_id = :institutionId AND it.deleted_at IS NULL AND t.deleted_at IS NULL
        ORDER BY t.first_name, t.last_name
        """)
    Flux<Teacher> findAllActiveByInstitutionId(Long institutionId);

    /** The active teacher profile behind an account, if any. */
    Mono<Teacher> findByAccountIdAndDeletedAtIsNull(Long accountId);
}
