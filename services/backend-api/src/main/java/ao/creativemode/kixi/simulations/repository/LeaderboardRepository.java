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

    /**
     * The enrollment every ranked account has to hold, for the requested group and year.
     *
     * <p>The year is not optional. A student who moves from school A to school B
     * keeps their A row ACTIVE forever — the state only ever goes to CANCELLED — so
     * without the year filter they would go on being ranked in A, with the marks they
     * took in B, under their own name.</p>
     *
     * <p>Left open on purpose: {@link #averagesSql} appends the group and the year
     * <em>inside</em> the parentheses, where they narrow this subquery rather than the
     * outer one. {@link #membershipSql} closes it.</p>
     */
    private static final String MEMBERSHIP = """
            EXISTS (SELECT 1
                      FROM enrollments e
                      JOIN classes cl ON cl.id = e.class_id AND cl.deleted_at IS NULL
                      JOIN accounts a ON a.id = e.account_id
                     WHERE e.account_id = s.account_id
                       AND e.deleted_at IS NULL
                       AND e.status = 'ACTIVE'
                       AND a.deleted_at IS NULL AND a.active = TRUE
            """;

    /** {@link #MEMBERSHIP} with the group's own conditions inside the parentheses. */
    private static String membershipSql(Scope scope, Long groupId) {
        return MEMBERSHIP + " AND " + groupPredicate(scope, groupId, "e", "cl")
                + " AND e.school_year_id = :schoolYearId)";
    }

    /**
     * A simulation whose open questions are still waiting for a teacher does not score.
     *
     * <p>Since #169 an open question is {@code PENDING_REVIEW} and {@code final_score}
     * only adds up what has been marked. Ranking that number against the whole
     * {@code total_max_score} would park a student below their peers until the teacher
     * took round to them, and the class would see it happen.</p>
     *
     * <p>Self-contained, so it closes its own parentheses.</p>
     */
    private static final String FULLY_MARKED = """
            NOT EXISTS (SELECT 1
                          FROM simulation_answers sa
                         WHERE sa.simulation_id = s.id
                           AND sa.deleted_at IS NULL
                           AND sa.review_status = 'PENDING_REVIEW')
            """;

    private final DatabaseClient databaseClient;

    public LeaderboardRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /**
     * Whether the statement is there to be ranked.
     *
     * <p>Counted rather than read, so the answer carries no data — this runs before the
     * id reaches a cache key.</p>
     */
    public Mono<Boolean> statementExists(Long statementId) {
        return databaseClient.sql("""
                        SELECT COUNT(*) AS total
                          FROM statements st
                         WHERE st.id = :statementId
                           AND st.deleted_at IS NULL
                        """)
                .bind("statementId", statementId)
                .map((row, metadata) -> ((Number) row.get("total")).longValue() > 0)
                .one()
                .defaultIfEmpty(false);
    }

    /** One average percentage per account that has a computable score in the group. */
    public Flux<GroupScore> findAverages(
            Scope scope, Long groupId, Long schoolYearId, Long statementId,
            LocalDateTime from, LocalDateTime to) {
        Sql sql = averagesSql(scope, groupId, schoolYearId, statementId, from, to);
        return bindAll(databaseClient.sql(sql.statement()), sql.binds())
                .map((row, metadata) -> new GroupScore(
                        ((Number) row.get("account_id")).longValue(),
                        row.get("average") == null ? null : ((Number) row.get("average")).doubleValue()))
                .all();
    }

    /** How many students belong to the group and year, ranked or not. */
    public Mono<Long> countMembers(Scope scope, Long groupId, Long schoolYearId) {
        return databaseClient.sql(membersSql(scope, groupId))
                .bind("groupId", groupId)
                .bind("schoolYearId", schoolYearId)
                .map((row, metadata) -> ((Number) row.get("total")).longValue())
                .one()
                .defaultIfEmpty(0L);
    }

    /**
     * The membership count, over the group and the school year.
     *
     * <p>Built pure and separately so the year can be checked without a database. It was
     * missing from here once, with {@code :schoolYearId} bound and never mentioned in the
     * statement: {@code DatabaseClient} ignores a bind with no placeholder in the SQL, so
     * the count silently went on adding up every year.</p>
     */
    static String membersSql(Scope scope, Long groupId) {
        return """
                SELECT COUNT(DISTINCT e.account_id) AS total
                  FROM enrollments e
                  JOIN classes cl ON cl.id = e.class_id AND cl.deleted_at IS NULL
                  JOIN accounts a ON a.id = e.account_id
                 WHERE e.deleted_at IS NULL AND e.status = 'ACTIVE'
                   AND a.deleted_at IS NULL AND a.active = TRUE
                   AND e.school_year_id = :schoolYearId AND """
                + groupPredicate(scope, groupId, "e", "cl");
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
            Scope scope, Long groupId, Long schoolYearId, Long statementId,
            LocalDateTime from, LocalDateTime to) {
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
        // A cohort is one school year, so the paper has to be from it too: last year's
        // average is not this year's result.
        where.add("s.school_year_id = :schoolYearId");
        where.add(FULLY_MARKED);
        where.add(membershipSql(scope, groupId));

        binds.put("schoolYearId", schoolYearId);
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

        // The newline matters: a text block strips trailing spaces, so "WHERE " alone
                // would arrive as "WHERE" and weld itself to the first condition.
String statement = """
                SELECT s.account_id AS account_id,
                       AVG(s.final_score * 100.0 / st.total_max_score) AS average
                  FROM simulations s
                  JOIN statements st ON st.id = s.statement_id
                 WHERE """
                + "\n  " + String.join("\n  AND ", where)
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