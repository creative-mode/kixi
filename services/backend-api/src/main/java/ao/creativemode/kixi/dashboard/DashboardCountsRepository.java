package ao.creativemode.kixi.dashboard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * The counts of the manager's dashboard, as {@code COUNT(*)} (issue #416). Every list the dashboard counted is
 * "active" in the same sense, {@code deleted_at IS NULL} (each service's {@code findAllActive}), so a count here is
 * the {@code length} of that list without reading the rows. One round trip for all of them.
 */
@Repository
public class DashboardCountsRepository {

    /** The manager's entity key (kixi-frontend {@code lib/crud/entities.tsx}) and its table, in the dashboard's order. */
    static final Map<String, String> TABLES = tables(
            "school-years", "school_years",
            "terms", "terms",
            "subjects", "subjects",
            "courses", "courses",
            "classes", "classes",
            "roles", "roles",
            "accounts", "accounts",
            "users", "users",
            "institutions", "institutions",
            "teachers", "teachers",
            "enrollments", "enrollments",
            "teaching-assignments", "teaching_assignments",
            "sessions", "sessions",
            "simulation-answers", "simulation_answers",
            "statements", "statements",
            "simulations", "simulations");

    /** What every staff member may list in full (the reference data): the same count for ADMIN and TEACHER. */
    static final List<String> REFERENCE = List.of("school-years", "terms", "subjects", "courses", "classes", "institutions");

    /** The statements waiting for review ({@code GET /statements/review}). */
    static final String REVIEW = "review";

    private final DatabaseClient database;

    public DashboardCountsRepository(DatabaseClient database) {
        this.database = database;
    }

    /** Every count the ADMIN sees, the review queue included. */
    public Mono<Map<String, Long>> all() {
        Map<String, String> queries = new LinkedHashMap<>();
        TABLES.forEach((key, table) -> queries.put(key, active(table)));
        queries.put(REVIEW, "SELECT COUNT(*) FROM statements WHERE needs_review = true AND deleted_at IS NULL");
        return counts(queries);
    }

    /** The reference data only. */
    public Mono<Map<String, Long>> reference() {
        Map<String, String> queries = new LinkedHashMap<>();
        REFERENCE.forEach(key -> queries.put(key, active(TABLES.get(key))));
        return counts(queries);
    }

    /** The caller's own enrollments: what {@code GET /enrollments} lists to anyone who is not ADMIN. */
    public Mono<Long> ownEnrollments(long accountId) {
        return database.sql("SELECT COUNT(*) AS n FROM enrollments WHERE account_id = :account AND deleted_at IS NULL")
                .bind("account", accountId)
                .map((row, meta) -> row.get("n", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    private static String active(String table) {
        return "SELECT COUNT(*) FROM " + table + " WHERE deleted_at IS NULL";
    }

    /**
     * One query with a sub-select per key. The table names come from {@link #TABLES}, never from the request; the
     * aliases are positional ({@code c0}, {@code c1}…) because the keys have dashes.
     */
    private Mono<Map<String, Long>> counts(Map<String, String> queries) {
        List<String> keys = List.copyOf(queries.keySet());
        String select = keys.stream()
                .map(key -> "(" + queries.get(key) + ") AS c" + keys.indexOf(key))
                .collect(Collectors.joining(", ", "SELECT ", ""));
        return database.sql(select)
                .map((row, meta) -> {
                    Map<String, Long> out = new LinkedHashMap<>();
                    for (int i = 0; i < keys.size(); i++) {
                        Long value = row.get("c" + i, Long.class);
                        out.put(keys.get(i), value == null ? 0L : value);
                    }
                    return out;
                })
                .one();
    }

    private static Map<String, String> tables(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return java.util.Collections.unmodifiableMap(map);
    }
}
