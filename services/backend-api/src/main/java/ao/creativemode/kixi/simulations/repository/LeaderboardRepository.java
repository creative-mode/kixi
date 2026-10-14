package ao.creativemode.kixi.simulations.repository;

import io.r2dbc.spi.Row;

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
 * <p>{@code scope=global} is the one cohort with no group behind it, and it is not a
 * filter over the others: it is built from CTEs, because it has to answer questions the
 * others never ask — which institution each student belongs to, how large that institution
 * is, and where the student stands inside it. See {@link #globalCtes} and ADR-0013.</p>
 *
 * <p>The SQL is built by pure static methods so the filters can be checked without a
 * database, following {@code StatementCatalogRepository}.</p>
 */
@Repository
public class LeaderboardRepository {

    /**
     * How many students an institution needs before it contributes to {@code scope=global}.
     *
     * <p>k-anonymity on the contributing side, and the answer to the question a
     * cross-institution pool cannot answer with {@link LeaderboardService}'s own floor:
     * that one hides the <em>bottom</em> of a small table, while this one stops a school
     * of three from being published at all. An institution whose students number in single
     * digits is identifiable by elimination — one student has a determinate rank — so the
     * whole institution is dropped, included and excluded as a block.</p>
     *
     * <p>Deliberately not the same constant as the small-group floor even though both are
     * ten: they answer different questions, and reusing one name for both is how the
     * elimination channel comes back.</p>
     */
    public static final int MIN_STUDENTS_PER_INSTITUTION = 10;

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
        if (scope == Scope.FRIENDS) {
            return FRIENDSHIP + ")";
        }
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

    /**
     * Has this student agreed to be ranked against students from other schools?
     *
     * <p>The gate on {@code scope=global} and nothing else. {@code class} and
     * {@code school} publish to people who were placed together; this is the only filter
     * that lets a stranger read a student's performance, so it is the only one that needs
     * the student to have said yes. Absence of a row means no — the ranking is opt-in, and
     * the default of a privacy switch is the private one.</p>
     *
     * <p>Written against the account column it is given rather than against one fixed
     * name, because the rows read it off a simulation and the count off a seat, and the
     * two have to be asking the same question: a count that includes a student the rows
     * exclude reports a cohort the table is not.</p>
     *
     * <p>Self-contained, so it closes its own parentheses.</p>
     */
    private static String consentedOn(String accountColumn) {
        return """
                EXISTS (SELECT 1
                          FROM leaderboard_consents lc
                         WHERE lc.account_id = %s
                           AND lc.cross_institution = TRUE
                           AND lc.revoked_at IS NULL)"""
                .formatted(accountColumn);
    }

    private static final String CONSENTED = consentedOn("s.account_id");

    /**
     * An accepted friendship with the caller.
     *
     * <p>A third shape, and the reason the other two do not stretch to cover it: a class
     * and a school are answered by a column comparison, and a friend is a pair of ids on
     * another table. {@code :groupId} carries the caller's account here rather than a
     * group, because the ranking and the friendship list have to be over the same people
     * — a count that disagrees with the rows would decide the podium on the wrong number.</p>
     *
     * <p>The other account is picked with a CASE rather than by two OR branches, so the
     * join to {@code accounts} has one key: a deactivated or deleted friend drops out at
     * the account, the same way it does for a class.</p>
     */
    private static final String FRIENDSHIP = """
            EXISTS (SELECT 1
                      FROM friendships f
                      JOIN accounts af ON af.id = CASE WHEN f.account_low = :groupId
                                                        THEN f.account_high
                                                        ELSE f.account_low END
                     WHERE f.deleted_at IS NULL
                       AND f.status = 'ACCEPTED'
                       AND (f.account_low = :groupId OR f.account_high = :groupId)
                       AND af.deleted_at IS NULL AND af.active = TRUE
                       AND s.account_id = af.id
                       AND EXISTS (SELECT 1
                                     FROM enrollments fe
                                     JOIN classes fc ON fc.id = fe.class_id
                                                   AND fc.deleted_at IS NULL
                                      WHERE fe.account_id = af.id
                                        AND fe.deleted_at IS NULL
                                        AND fe.status = 'ACTIVE'
                                        AND fe.school_year_id = :schoolYearId
                                        AND fc.institution_id = :institutionId)
            """;

    private final DatabaseClient databaseClient;

    public LeaderboardRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /**
     * How many friends the ranking may show.
     *
     * <p>Not the enrollment count with a different filter: friends are counted on the
     * friendships table, under the same conditions as {@link #FRIENDSHIP}. If the two
     * disagreed, {@code totalStudents} would decide the small-group floor from a number
     * that is not the number of rows behind it.</p>
     */
    private static final String FRIEND_COUNT = """
            SELECT COUNT(*) AS total
              FROM friendships f
              JOIN accounts af ON af.id = CASE WHEN f.account_low = :groupId
                                                THEN f.account_high ELSE f.account_low END
             WHERE f.deleted_at IS NULL
               AND f.status = 'ACCEPTED'
               AND (f.account_low = :groupId OR f.account_high = :groupId)
               AND af.deleted_at IS NULL AND af.active = TRUE
               AND EXISTS (SELECT 1
                             FROM enrollments fe
                             JOIN classes fc ON fc.id = fe.class_id
                                           AND fc.deleted_at IS NULL
                              WHERE fe.account_id = af.id
                                AND fe.deleted_at IS NULL
                                AND fe.status = 'ACTIVE'
                                AND fe.school_year_id = :schoolYearId
                                AND fc.institution_id = :institutionId)
            """;

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

    /** Whether the student agreed to be ranked against other schools (ADR-0013). */
    public Mono<Boolean> consentedToCrossInstitution(Long accountId) {
        return databaseClient.sql("""
                        SELECT COUNT(*) AS total
                          FROM leaderboard_consents lc
                         WHERE lc.account_id = :accountId
                           AND lc.cross_institution = TRUE
                           AND lc.revoked_at IS NULL
                        """)
                .bind("accountId", accountId)
                .map((row, metadata) -> ((Number) row.get("total")).longValue() > 0)
                .one()
                .defaultIfEmpty(false);
    }

    /**
     * Gives or takes back the consent, in one statement.
     *
     * <p>An upsert rather than an insert-then-update, so two clicks cannot leave two rows
     * disagreeing about the same student — the unique index makes that impossible, and
     * this makes it a non-event instead of an error. {@code consented_at} is kept on
     * revoke: {@code revoked_at} already records when the answer changed, and a revoked
     * row is not a fresh "yes".</p>
     */
    public Mono<Boolean> setCrossInstitutionConsent(Long accountId, boolean consented) {
        return databaseClient.sql("""
                        INSERT INTO leaderboard_consents
                               (account_id, cross_institution, consented_at, revoked_at)
                        VALUES (:accountId, :consented, now(), CASE WHEN :consented THEN NULL
                                                                    ELSE now() END)
                        ON CONFLICT (account_id) DO UPDATE
                           SET cross_institution = EXCLUDED.cross_institution,
                               revoked_at = EXCLUDED.revoked_at,
                               updated_at = now()
                        """)
                .bind("accountId", accountId)
                .bind("consented", consented)
                // rowsUpdated, not a returning row: the consent is not data anybody reads,
                // and the count is the answer to "did it land" without fetching it back.
                .fetch()
                .rowsUpdated()
                .map(rows -> rows > 0)
                .defaultIfEmpty(false);
    }

    /** One average percentage per account that has a computable score in the group. */
    public Flux<GroupScore> findAverages(
            Scope scope, Long groupId, Long schoolYearId, Long institutionId, Long statementId,
            LocalDateTime from, LocalDateTime to) {
        Sql sql = averagesSql(scope, groupId, schoolYearId, institutionId, statementId, from, to);
        return bindAll(databaseClient.sql(sql.statement()), sql.binds())
                .map((row, metadata) -> new GroupScore(
                        ((Number) row.get("account_id")).longValue(),
                        longOrNull(row, "institution_id"),
                        decimalOrNull(row, "average"),
                        decimalOrNull(row, "percentile")))
                .all();
    }

    /**
     * A column the statement did not select is absent, and absent is not the same as null.
     *
     * <p>{@code Row.get} throws {@code NoSuchElementException} for a name that is not in
     * the result set, so the cross-institution columns — which {@code scope=class} and
     * {@code school} do not select, and which they have no value for anyway — are asked
     * about rather than read. Reading them straight would have made the scopes from #117
     * fail at runtime the first time the ranking was read for a class.</p>
     */
    private static Long longOrNull(Row row, String column) {
        return row.getMetadata().contains(column) ? ((Number) row.get(column)).longValue() : null;
    }

    private static Double decimalOrNull(Row row, String column) {
        return row.getMetadata().contains(column) && row.get(column) != null
                ? ((Number) row.get(column)).doubleValue()
                : null;
    }

    /** How many students belong to the group and year, ranked or not. */
    public Mono<Long> countMembers(
            Scope scope, Long groupId, Long schoolYearId, Long institutionId) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(membersSql(scope, groupId));
        // Only where the statement mentions it. An unmatched bind is dropped without a
        // word by DatabaseClient, which is harmless — and hides exactly the kind of
        // mismatch that once let the year go missing from this count.
        if (scope == Scope.GLOBAL) {
            // No group to name: `membersSql(GLOBAL, …)` filters on the year and on
            // :institutionMinStudents, and binding a groupId it never mentions would be
            // the same silent mismatch, in the other direction.
            spec = spec.bind("schoolYearId", schoolYearId)
                    .bind("institutionMinStudents", MIN_STUDENTS_PER_INSTITUTION);
        } else {
            // The year binds for every scope that has one; friends adds the school it is
            // isolated by. Written as one chain so the year cannot be added to one branch
            // and forgotten in the other.
            spec = spec.bind("groupId", groupId).bind("schoolYearId", schoolYearId);
            if (scope == Scope.FRIENDS) {
                spec = spec.bind("institutionId", institutionId == null ? 0L : institutionId);
            }
        }
        return spec
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
        if (scope == Scope.FRIENDS) {
            return FRIEND_COUNT;
        }
        if (scope == Scope.GLOBAL) {
            return globalCtes(withConsent(rankableScoreConditions())) + "\n" + """
                    SELECT COUNT(*) AS total
                      FROM seat sd
                     WHERE sd.institution_id IN (SELECT institution_id FROM big_enough)
                    """
                    // The "AND " carries its space on this side of the join: a text block
                    // strips trailing whitespace, so a lone "AND" arrives welded to the
                    // next token and the statement will not parse.
                    + " AND " + consentedOn("sd.account_id");
        }
        return """
                SELECT COUNT(DISTINCT e.account_id) AS total
                  FROM enrollments e
                  JOIN classes cl ON cl.id = e.class_id AND cl.deleted_at IS NULL
                  JOIN accounts a ON a.id = e.account_id
                 WHERE e.deleted_at IS NULL AND e.status = 'ACTIVE'
                   AND a.deleted_at IS NULL AND a.active = TRUE
                   AND e.school_year_id = :schoolYearId AND """
                // The space sits on this side of the join: the text block above strips the
                // one after "AND", so without it the predicate arrives as "ANDe.class_id".
                + " " + groupPredicate(scope, groupId, "e", "cl");
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

    /**
     * The conditions that decide whether a simulation can be ranked at all.
     *
     * <p>One list, read by the rows and by the count, because {@code totalStudents}
     * decides the small-group floor and therefore has to be counting the same students
     * the rows behind it are. Written twice they would agree today and be edited apart
     * tomorrow — and the failure is invisible, because both halves still return rows.</p>
     *
     * <p>Ordered as the narrative above it: what is real, then what is finished, then
     * what has a scale, then what year it belongs to, then what has nothing left
     * unmarked.</p>
     */
    private static List<String> rankableScoreConditions() {
        return List.of(
                "s.deleted_at IS NULL",
                // A statement that was taken down stops counting the moment it is taken
                // down, for the same reason StatementCatalogRepository hides it from the catalog.
                "st.deleted_at IS NULL",
                "s.status = 'FINISHED'",
                "s.final_score IS NOT NULL",
                // A score without a scale cannot be turned into a percentage, and comparing
                // it against one that has a scale would be meaningless.
                "st.total_max_score IS NOT NULL AND st.total_max_score > 0",
                // A cohort is one school year, so the paper has to be from it too: last
                // year's average is not this year's result.
                "s.school_year_id = :schoolYearId",
                FULLY_MARKED);
    }

    /** {@link #rankableScoreConditions} plus the cross-institution consent gate. */
    private static List<String> withConsent(List<String> where) {
        List<String> consented = new ArrayList<>(where);
        consented.add(CONSENTED);
        return consented;
    }

    static Sql averagesSql(
            Scope scope, Long groupId, Long schoolYearId, Long institutionId, Long statementId,
            LocalDateTime from, LocalDateTime to) {
        // Mutable: the statement and period filters are appended below, and the caller
        // never sees a condition added on its behalf.
        List<String> where = new ArrayList<>(rankableScoreConditions());
        Map<String, Object> binds = new LinkedHashMap<>();

        binds.put("schoolYearId", schoolYearId);
        if (scope == Scope.FRIENDS) {
            // Only friends needs it, and a null one means the caller sits in a class
            // that belongs to no school: there is nothing to isolate by, so the cohort
            // is empty rather than every friend of everywhere.
            binds.put("institutionId", institutionId == null ? 0L : institutionId);
        }
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

        if (scope == Scope.GLOBAL) {
            where.add(CONSENTED);
            binds.put("institutionMinStudents", MIN_STUDENTS_PER_INSTITUTION);
            return new Sql(globalCtes(where) + "\n" + """
                    SELECT pa.account_id AS account_id,
                           pa.institution_id AS institution_id,
                           pa.average AS average,
                           -- *100 so the number a client reads is a percentile and not a
                           -- fraction. CUME_DIST and not PERCENT_RANK: the best student of
                           -- a ten-student school and of a five-hundred-student school both
                           -- come out at 100, which is what "top of your school" means.
                           -- PERCENT_RANK would cap the small school at 0.9 and let it
                           -- never reach the top of a cross-institution table at all.
                           CUME_DIST() OVER (PARTITION BY pa.institution_id
                                              ORDER BY pa.average) * 100 AS percentile
                      FROM per_account pa
                     WHERE pa.institution_id IN (SELECT institution_id FROM big_enough)
                     ORDER BY percentile DESC, pa.average DESC, pa.account_id ASC
                    """, binds);
        }

        where.add(membershipSql(scope, groupId));

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

    /**
     * The CTEs behind {@code scope=global}, shared by the rows and the count.
     *
     * <p>Two shapes the other scopes do not need, both of them about the institution:</p>
     *
     * <ul>
     *   <li><b>Which school is each student at.</b> A class carries it, but a ranking has
     *       to have it per student, and an enrollment join would multiply a student's
     *       simulations once per matching row. {@code DISTINCT ON} collapses the seat to
     *       one row per account first, newest first — the same rule
     *       {@code InstitutionEnrollmentSeatReader} applies — so the join downstream
     *       cannot multiply anything.</li>
     *   <li><b>Which schools are big enough to publish.</b> That is a count over the
     *       students themselves, so it cannot be a filter written next to the row: it is
     *       its own pass over {@code per_account}.</li>
     * </ul>
     *
     * <p>The count and the rows share this text verbatim, for the reason {@link #FRIEND_COUNT}
     * exists: {@code totalStudents} decides the small-group floor, so it has to be counting
     * the same people the rows behind it are. Two separately written versions would agree
     * today and be edited apart tomorrow.</p>
     *
     * <p>{@code institution_id IS NOT NULL} is a guard, not a hope: {@code classes} declares
     * the column NOT NULL, and a null partitioning key is a ranking in which every
     * unplaceable student shares one percentile.</p>
     */
    private static String globalCtes(List<String> where) {
        return """
                WITH seat AS (
                    SELECT DISTINCT ON (e.account_id)
                           e.account_id AS account_id,
                           cl.institution_id AS institution_id
                      FROM enrollments e
                      JOIN classes cl ON cl.id = e.class_id AND cl.deleted_at IS NULL
                      JOIN accounts a ON a.id = e.account_id
                     WHERE e.deleted_at IS NULL AND e.status = 'ACTIVE'
                       AND a.deleted_at IS NULL AND a.active = TRUE
                       AND e.school_year_id = :schoolYearId
                     ORDER BY e.account_id, e.created_at DESC, e.id DESC
                ),
                per_account AS (
                    SELECT s.account_id AS account_id,
                           sd.institution_id AS institution_id,
                           AVG(s.final_score * 100.0 / st.total_max_score) AS average
                      FROM simulations s
                      JOIN statements st ON st.id = s.statement_id
                      JOIN seat sd ON sd.account_id = s.account_id
                     WHERE """
                // A text block strips trailing spaces, so "WHERE " on its own arrives as
                // "WHERE" and welds itself to the first condition. Same trap as the
                // statement below, which is why both end in a join and not a literal.
                + "\n                   " + String.join("\n                   AND ", where)
                + """
                               GROUP BY s.account_id, sd.institution_id
                ),
                big_enough AS (
                    SELECT institution_id
                      FROM per_account
                     WHERE institution_id IS NOT NULL
                     GROUP BY institution_id
                    HAVING COUNT(*) >= :institutionMinStudents
                )
                """;
    }

    /** The one place that knows how a group maps onto a class or a school. */
    static String groupPredicate(Scope scope, Long groupId, String enrollment, String klass) {
        return switch (scope) {
            case CLASS -> enrollment + ".class_id = :groupId";
            case SCHOOL -> klass + ".institution_id = :groupId";
            // Unreachable: FRIENDS never reaches here — membershipSql returns FRIENDSHIP
            // whole, because a friendship is not a column on enrollments. GLOBAL never
            // reaches here either: it has no group at all, and averagingSql routes it to
            // the CTEs above. Failing loudly beats emitting `null.class_id = :groupId`
            // and finding out at runtime.
            case FRIENDS, GLOBAL -> throw new IllegalArgumentException(
                    scope + " is not a group on enrollments; use membershipSql");
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
        CLASS, SCHOOL, FRIENDS, GLOBAL;

        public static Scope of(String value) {
            for (Scope scope : values()) {
                if (scope.name().equalsIgnoreCase(value)) {
                    return scope;
                }
            }
            throw new IllegalArgumentException("Unsupported leaderboard scope: " + value);
        }
    }

    /**
     * A student's average, as a percentage of the maximum score of what they sat.
     *
     * @param institutionId which school the student is at, or null outside
     *                      {@code scope=global} — the only scope that has to name one
     * @param percentile    where the student stands inside their own institution, 0 to
     *                      100, or null outside {@code scope=global}
     */
    public record GroupScore(Long accountId, Long institutionId, Double average, Double percentile) { }

    public record DisplayName(Long accountId, String firstName, String lastName) { }

    record Sql(String statement, Map<String, Object> binds) { }
}
