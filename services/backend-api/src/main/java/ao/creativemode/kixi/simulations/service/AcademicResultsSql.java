package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.shared.service.AcademicResultsReader;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** {@link AcademicResultsReader} over the simulation tables, which belong to this module. */
@Component
class AcademicResultsSql implements AcademicResultsReader {

    private final DatabaseClient database;

    AcademicResultsSql(DatabaseClient database) {
        this.database = database;
    }

    @Override
    public Mono<List<Result>> finishedIn(Long accountId, Long schoolYearId) {
        return database.sql("""
                SELECT s.id, st.subject_id, sub.name AS subject, st.title, s.final_score,
                       st.total_max_score, s.finished_at, s.exam_room_id
                  FROM simulations s
                  JOIN statements st ON st.id = s.statement_id
                  LEFT JOIN subjects sub ON sub.id = st.subject_id
                 WHERE s.account_id = :account AND s.deleted_at IS NULL AND s.status = 'FINISHED'
                   AND COALESCE(
                         s.school_year_id,
                         st.school_year_id,
                         (SELECT y.id FROM school_years y
                           WHERE y.deleted_at IS NULL AND s.finished_at IS NOT NULL
                             AND y.start_year = CASE WHEN EXTRACT(MONTH FROM s.finished_at) >= 9
                                                     THEN EXTRACT(YEAR FROM s.finished_at)::int
                                                     ELSE EXTRACT(YEAR FROM s.finished_at)::int - 1 END
                           ORDER BY y.id LIMIT 1)) = :year
                 ORDER BY s.finished_at, s.id
                """)
            .bind("account", accountId).bind("year", schoolYearId)
            .map(row -> new Result(
                row.get("id", Long.class), row.get("subject_id", Long.class), row.get("subject", String.class),
                row.get("title", String.class), number(row.get("final_score", BigDecimal.class)),
                number(row.get("total_max_score", BigDecimal.class)),
                row.get("finished_at", OffsetDateTime.class), row.get("exam_room_id", Long.class) != null))
            .all().collectList();
    }

    private static Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
