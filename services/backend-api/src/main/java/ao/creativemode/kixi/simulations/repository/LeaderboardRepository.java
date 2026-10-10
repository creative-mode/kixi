package ao.creativemode.kixi.simulations.repository;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ranking behind {@code GET /api/v1/leaderboard}.
 *
 * <p>A derived query cannot express it. A score only means something relative to the
 * statement it came from — 12.5 out of 20 is not "worse" than 80 out of 100 — so the
 * average is taken over percentages, and statements without a maximum score are left
 * out rather than compared against a scale they do not share.</p>
 *
 * <p>Both columns are {@code DECIMAL(10,2)}, so the division and the average are exact
 * {@code numeric} arithmetic, not floating point. Rounding it was tried and removed: it
 * put averages that genuinely differed into the same place, and it changed the number
 * the client sees.</p>
 *
 * <p>Group membership is resolved with {@code EXISTS} rather than a join on purpose:
 * an enrollment is unique per school year, but joining would still multiply a
 * student's simulations by every enrollment that matched, and their average would
 * quietly be computed over a different set of rows than anyone else's.</p>
 *
 * <p>The SQL is built by a pure static method so the filters can be checked without a
 * database, following {@code StatementCatalogRepository}.</p>
 */
@Repository
public class LeaderboardRepository {

    /** The enrollment every ranked account has to hold, for the requested group. */
    private static final String MEMBERSHIP = """
            EXISTS (SELECT 1
                      FROM enrollments e
                      JOIN classes cl ON cl.id = e.class_id AND cl.deleted_at IS NULL
                     WHERE e.account_id = s.account_id
                       AND e.deleted_at IS NULL
                       AND e.status = 'ACTIVE'
            """;

    private final DatabaseClient databaseClient;

    public LeaderboardRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /** One average percentage per account that has a computable score in the group. */
    public Flux<GroupScore> findAverages(
            Scope scope, Long groupId, Long statementId, LocalDateTime from, LocalDateTime to) {
        Sql sql = averagesSql(scope, groupId, statementId, from, to);
        return bindAll(databaseClient.sql(sql.statement()), sql.binds())
                .map((row, metadata) -> new GroupScore(
                        ((Number) row.get("account_id")).longValue(),
                        row.get("average") == null ? null : ((Number) row.get("average")).doubleValue()))
                .all();
    }

    /** How many students belong to the group, ranked or not. */
    public Mono<Long> countMembers(Scope scope, Long groupId) {
        String where = groupPredicate(scope, groupId, "e", "cl");
        return databaseClient.sql("""
                        SELECT COUNT(DISTINCT e.account_id) AS total
                          FROM enrollments e
                          JOIN classes cl ON cl.id = e.class_id AND cl.deleted_at IS NULL
                        WHERE e.deleted_at IS NULL AND e.status = 'ACTIVE' AND """
                        + where)
                .bind("groupId", groupId)
                .map((row, metadata) -> ((Number) row.get("total")).longValue())
                .one()
                .defaultIfEmpty(0L);
    }

    /** Names for the handful of accounts a caller is allowed to see. */
    public Flux<DisplayName> findDisplayNames(Collection<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return Flux.empty();
        }
        return databaseClient.sql("""
                        SELECT u.account_id AS account_id, u.first_name, u.last_name
                          FROM users u
                         WHERE u.deleted_at IS NULL
                           AND u.account_id = ANY(:accountIds)
                        """)
                .bind("accountIds", accountIds.toArray(Long[]::new))
                .map((row, metadata) -> new DisplayName(
                        ((Number) row.get("account_id")).longValue(),
                        (String) row.get("first_name"),
                        (String) row.get("last_name")))
                .all();
    }

    static Sql averagesSql(
            Scope scope, Long groupId, Long statementId, LocalDateTime from, LocalDateTime to) {
        List<String> where = new ArrayList<>();
        Map<String, Object> binds = new LinkedHashMap<>();

        where.add("s.deleted_at IS NULL");
        // A statement that was taken down stops counting the moment it is taken down,
        // for the same reason StatementCatalogRepository hides it from the catalog.
        where.add("st.deleted_at IS NULL");
        where.add("s.status = 'FINISHED'");
        where.add("s.final_score IS NOT NULL");
        // A score without a scale cannot be turned into a percentage, and comparing it
        // against one that has a scale would be meaningless.
        where.add("st.total_max_score IS NOT NULL AND st.total_max_score > 0");
        where.add(MEMBERSHIP + " AND " + groupPredicate(scope, groupId, "e", "cl"));

        if (statementId != null) {
            where.add("s.statement_id = :statementId");
            binds.put("statementId", statementId);
        }
        if (from != null) {
            where.add("s.finished_at >= :from");
            binds.put("from", from);
        }
        if (to != null) {
            where.add("s.finished_at < :to");
            binds.put("to", to);
        }

        String statement = """
                SELECT s.account_id AS account_id,
                       AVG(s.final_score * 100.0 / st.total_max_score) AS average
                  FROM simulations s
                  JOIN statements st ON st.id = s.statement_id
                WHERE """
                + String.join("\n  AND ", where)
                + "\n GROUP BY s.account_id"
                // Ties break on the id so the same group always yields the same order,
                // which matters once the result is cached and shared.
                + "\n ORDER BY average DESC NULLS LAST, s.account_id ASC";

        binds.put("groupId", groupId);
        return new Sql(statement, binds);
    }

    /** The one place that knows how a group maps onto a class or a school. */
    private static String groupPredicate(Scope scope, Long groupId, String enrollment, String klass) {
        return switch (scope) {
            case CLASS -> enrollment + ".class_id = :groupId";
            case SCHOOL -> klass + ".institution_id = :groupId";
        };
    }

    private static DatabaseClient.GenericExecuteSpec bindAll(
            DatabaseClient.GenericExecuteSpec spec, Map<String, Object> binds) {
        for (Map.Entry<String, Object> bind : binds.entrySet()) {
            spec = spec.bind(bind.getKey(), bind.getValue());
        }
        return spec;
    }

    /** Which cohort the caller is compared against. */
    public enum Scope {
        CLASS, SCHOOL;

        public static Scope of(String value) {
            for (Scope scope : values()) {
                if (scope.name().equalsIgnoreCase(value)) {
                    return scope;
                }
            }
            throw new IllegalArgumentException("Unsupported leaderboard scope: " + value);
        }
    }

    /** A student's average, as a percentage of the maximum score of what they sat. */
    public record GroupScore(Long accountId, Double average) { }

    public record DisplayName(Long accountId, String firstName, String lastName) { }

    record Sql(String statement, Map<String, Object> binds) { }
}