package ao.creativemode.kixi.institutions.repository;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * The compact academic lookup used by the student statement catalog.
 *
 * <p>The profile path intentionally resolves the full academic context. The
 * catalog only needs three ids, so keeping this query in the repository avoids
 * paying for a second round-trip to resolve the class after reading the
 * enrollment.</p>
 */
@Repository
public class EnrollmentCatalogQuery {

    private static final String CURRENT_AFFILIATION = """
            SELECT c.institution_id, c.course_id, c.id AS class_id
            FROM enrollments e
            JOIN classes c ON c.id = e.class_id
            WHERE e.account_id = :accountId
              AND e.deleted_at IS NULL
              AND e.status = 'ACTIVE'
              AND c.deleted_at IS NULL
            ORDER BY e.id DESC
            LIMIT 1
            """;

    private final DatabaseClient database;

    public EnrollmentCatalogQuery(DatabaseClient database) {
        this.database = database;
    }

    public Mono<CatalogAffiliation> findCurrentActiveByAccountId(Long accountId) {
        return database.sql(CURRENT_AFFILIATION)
                .bind("accountId", accountId)
                .map((row, metadata) -> new CatalogAffiliation(
                        row.get("institution_id", Long.class),
                        row.get("course_id", Long.class),
                        row.get("class_id", Long.class)))
                .one();
    }

    public record CatalogAffiliation(Long institutionId, Long courseId, Long classId) { }
}
