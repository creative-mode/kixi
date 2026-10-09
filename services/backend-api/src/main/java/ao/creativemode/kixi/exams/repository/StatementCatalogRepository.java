package ao.creativemode.kixi.exams.repository;

import ao.creativemode.kixi.exams.model.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.r2dbc.convert.R2dbcConverter;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The student-facing catalog of statements: visible, not deleted, filtered by
 * whatever the student picked and paged.
 *
 * <p>A derived query cannot express it. A statement does not always carry its school
 * or course directly — one set for a class inherits them from that class, which carries
 * the school of its course (V30) — so filtering by school, course or "classe" has to
 * look through the class. The SQL is built from the filters that are present only, and
 * every value goes in as a bind parameter.</p>
 */
@Repository
public class StatementCatalogRepository {

    /**
     * Where a statement's school, course, year and "classe" really come from: its own
     * column when it has one, otherwise the class it was set for (and that class's course).
     */
    private static final String FROM = """
            FROM statements s
            LEFT JOIN classes cl ON cl.id = s.class_id
            LEFT JOIN courses co ON co.id = COALESCE(s.course_id, cl.course_id)
            """;

    private static final String INSTITUTION = "COALESCE(s.institution_id, cl.institution_id, co.institution_id)";
    private static final String COURSE = "COALESCE(s.course_id, cl.course_id)";
    private static final String SCHOOL_YEAR = "COALESCE(s.school_year_id, cl.school_year_id)";

    private final DatabaseClient databaseClient;
    private final R2dbcConverter converter;

    public StatementCatalogRepository(DatabaseClient databaseClient, R2dbcConverter converter) {
        this.databaseClient = databaseClient;
        this.converter = converter;
    }

    /** The requested page, in the requested order. */
    public Flux<Statement> findPage(Criteria criteria) {
        Sql sql = build(criteria);
        return bindAll(databaseClient.sql(sql.select()), sql.binds())
                .map((row, metadata) -> converter.read(Statement.class, row, metadata))
                .all();
    }

    /** How many statements match across every page. */
    public Mono<Long> count(Criteria criteria) {
        Sql sql = build(criteria);
        return bindAll(databaseClient.sql(sql.count()), sql.countBinds())
                .map((row, metadata) -> ((Number) row.get("total")).longValue())
                .one()
                .defaultIfEmpty(0L);
    }

    private static DatabaseClient.GenericExecuteSpec bindAll(
            DatabaseClient.GenericExecuteSpec spec, Map<String, Object> binds) {
        for (Map.Entry<String, Object> bind : binds.entrySet()) {
            spec = spec.bind(bind.getKey(), bind.getValue());
        }
        return spec;
    }

    /**
     * Builds the page and count statements for the criteria. Package-private and pure so
     * the SQL can be checked without a database.
     */
    static Sql build(Criteria criteria) {
        List<String> where = new ArrayList<>();
        Map<String, Object> binds = new LinkedHashMap<>();

        where.add("s.deleted_at IS NULL");
        where.add("s.visible = TRUE");

        if (criteria.institutionId() != null) {
            where.add(INSTITUTION + " = :institutionId");
            binds.put("institutionId", criteria.institutionId());
        }
        if (criteria.courseId() != null) {
            where.add(COURSE + " = :courseId");
            binds.put("courseId", criteria.courseId());
        }
        if (criteria.classId() != null) {
            where.add("s.class_id = :classId");
            binds.put("classId", criteria.classId());
        }
        if (criteria.grade() != null) {
            // The "classe" only exists on the class: statements have no grade column. So a
            // statement linked to a course but to no class has no grade to match and is
            // left out when filtering by grade. Intentional until statements carry one.
            where.add("cl.grade = :grade");
            binds.put("grade", criteria.grade());
        }
        if (criteria.subjectId() != null) {
            where.add("s.subject_id = :subjectId");
            binds.put("subjectId", criteria.subjectId());
        }
        if (criteria.schoolYearId() != null) {
            where.add(SCHOOL_YEAR + " = :schoolYearId");
            binds.put("schoolYearId", criteria.schoolYearId());
        }
        if (criteria.termId() != null) {
            where.add("s.term_id = :termId");
            binds.put("termId", criteria.termId());
        }
        if (criteria.examType() != null) {
            where.add("LOWER(s.exam_type) = LOWER(:examType)");
            binds.put("examType", criteria.examType());
        }
        if (criteria.query() != null) {
            where.add("(s.title ILIKE :query OR s.exam_type ILIKE :query)");
            binds.put("query", "%" + escapeLike(criteria.query()) + "%");
        }

        String whereClause = "WHERE " + String.join("\n  AND ", where) + "\n";

        String orderBy = orderBy(criteria, binds);
        binds.put("limit", criteria.limit());
        binds.put("offset", criteria.offset());

        String select = "SELECT s.*\n" + FROM + whereClause
                + "ORDER BY " + orderBy + "\n"
                + "LIMIT :limit OFFSET :offset";
        String count = "SELECT COUNT(*) AS total\n" + FROM + whereClause;

        // The count shares the filters but not the order or the page window.
        Map<String, Object> countBinds = new LinkedHashMap<>(binds);
        countBinds.remove("limit");
        countBinds.remove("offset");
        countBinds.remove("affinityClassId");
        countBinds.remove("affinityCourseId");

        return new Sql(select, count, binds, countBinds);
    }

    /**
     * Relevance puts the student's own class first and then the rest of their course, so
     * the first page is what they are most likely studying for. Ties, and the other sorts,
     * end on the id so paging never repeats or skips a row between pages.
     */
    private static String orderBy(Criteria criteria, Map<String, Object> binds) {
        return switch (criteria.sort()) {
            case RECENT -> "s.created_at DESC, s.id DESC";
            case OLDEST -> "s.created_at ASC, s.id ASC";
            case TITLE -> "LOWER(s.title) ASC NULLS LAST, s.id ASC";
            case RELEVANCE -> {
                List<String> affinity = new ArrayList<>();
                if (criteria.affinityClassId() != null) {
                    affinity.add("WHEN s.class_id = :affinityClassId THEN 0");
                    binds.put("affinityClassId", criteria.affinityClassId());
                }
                if (criteria.affinityCourseId() != null) {
                    affinity.add("WHEN " + COURSE + " = :affinityCourseId THEN 1");
                    binds.put("affinityCourseId", criteria.affinityCourseId());
                }
                String recent = "s.created_at DESC, s.id DESC";
                yield affinity.isEmpty()
                        ? recent
                        : "CASE " + String.join(" ", affinity) + " ELSE 2 END, " + recent;
            }
        };
    }

    /** Free text is matched literally: %, _ and the escape itself lose their meaning. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public enum Sort { RELEVANCE, RECENT, OLDEST, TITLE }

    /**
     * The catalog filters once defaults are resolved. {@code affinityClassId} and
     * {@code affinityCourseId} only order the result (the student's own class and course
     * first); they never filter it.
     */
    public record Criteria(
            Long institutionId,
            Long courseId,
            Long classId,
            Integer grade,
            Long subjectId,
            Long schoolYearId,
            Long termId,
            String examType,
            String query,
            Long affinityClassId,
            Long affinityCourseId,
            Sort sort,
            int limit,
            long offset
    ) { }

    record Sql(String select, String count, Map<String, Object> binds, Map<String, Object> countBinds) { }
}
