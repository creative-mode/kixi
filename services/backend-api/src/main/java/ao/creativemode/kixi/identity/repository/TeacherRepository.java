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

    /**
     * Active teachers at any school whose name or specialty contains the pattern (already
     * lower-cased, with {@code %} around it), for the profile search (issue #406).
     *
     * <p>Only teachers linked to a school that still exists are returned — the same set a
     * viewer already sees on a school's profile. {@code DISTINCT} keeps a teacher at two
     * schools a single row; the schools are resolved separately.</p>
     */
    @Query("""
        SELECT DISTINCT t.* FROM teachers t
        JOIN institution_teachers it ON it.teacher_id = t.id AND it.deleted_at IS NULL
        JOIN institutions i ON i.id = it.institution_id AND i.deleted_at IS NULL
        WHERE t.deleted_at IS NULL
          AND (LOWER(t.first_name) LIKE :pattern
               OR LOWER(t.last_name) LIKE :pattern
               OR LOWER(COALESCE(t.specialty, '')) LIKE :pattern)
        ORDER BY t.first_name, t.last_name
        LIMIT :limit
        """)
    Flux<Teacher> searchActiveByText(String pattern, int limit);

    /** The active teacher profile behind an account, if any. */
    Mono<Teacher> findByAccountIdAndDeletedAtIsNull(Long accountId);
}
