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
