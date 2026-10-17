package ao.creativemode.kixi.institutions.repository;

import ao.creativemode.kixi.institutions.model.Institution;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface InstitutionRepository extends ReactiveCrudRepository<Institution, Long> {

    Flux<Institution> findAllByDeletedAtIsNull();
    Flux<Institution> findAllByDeletedAtIsNotNull();
    Mono<Institution> findByIdAndDeletedAtIsNull(Long id);
    Mono<Institution> findByIdAndDeletedAtIsNotNull(Long id);

    /** Schools whose name, short name or code contains the pattern (already lower-cased, with % around it). */
    @Query("""
        SELECT * FROM institutions
        WHERE deleted_at IS NULL
          AND (LOWER(name) LIKE :pattern OR LOWER(COALESCE(short_name, '')) LIKE :pattern OR LOWER(code) LIKE :pattern)
        ORDER BY verified DESC, name
        LIMIT :limit
        """)
    Flux<Institution> search(String pattern, int limit);

    @Query("SELECT COUNT(*) FROM institution_teachers WHERE institution_id = :id AND deleted_at IS NULL")
    Mono<Long> countActiveTeachers(Long id);

    @Query("SELECT COUNT(*) FROM institution_students WHERE institution_id = :id AND deleted_at IS NULL")
    Mono<Long> countActiveStudents(Long id);

    /**
     * Statements a student can see that belong to the school, by the same resolution the catalog uses:
     * the statement's own school, otherwise its class's, otherwise its course's.
     */
    @Query("""
        SELECT COUNT(*) FROM statements s
        LEFT JOIN classes cl ON cl.id = s.class_id
        LEFT JOIN courses co ON co.id = COALESCE(s.course_id, cl.course_id)
        WHERE s.deleted_at IS NULL AND s.visible = TRUE AND s.needs_review = FALSE
          AND COALESCE(s.institution_id, cl.institution_id, co.institution_id) = :id
        """)
    Mono<Long> countVisibleStatements(Long id);

    /**
     * Exam rooms of the school's classes that left draft. Drafts are the teacher's private work, so
     * they do not count; plain SQL on purpose, since the institutions module does not depend on
     * examrooms.
     */
    @Query("""
        SELECT COUNT(*) FROM exam_rooms r
        JOIN classes cl ON cl.id = r.class_id
        WHERE cl.institution_id = :id AND r.status <> 'DRAFT'
        """)
    Mono<Long> countPublishedRooms(Long id);

    /** Schools with at least one active class in the school year (issue #253), by name. */
    @Query("""
        SELECT i.* FROM institutions i
        WHERE i.deleted_at IS NULL
          AND EXISTS (SELECT 1 FROM classes c
                      WHERE c.institution_id = i.id AND c.deleted_at IS NULL AND c.school_year_id = :schoolYearId)
        ORDER BY i.name
        """)
    Flux<Institution> findAllActiveWithClassesInYear(Long schoolYearId);
}
