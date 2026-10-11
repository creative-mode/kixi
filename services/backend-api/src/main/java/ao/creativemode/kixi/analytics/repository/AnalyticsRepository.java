package ao.creativemode.kixi.analytics.repository;

import io.r2dbc.spi.Readable;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Every aggregate behind {@code /api/v1/analytics/*}.
 *
 * <p>Each endpoint is a fixed number of grouped queries, never a query per row: the
 * dashboard would otherwise be one round trip per statement, per class, per institution.
 * Answers are read straight off the row because none of these tables has an entity worth
 * mapping.</p>
 *
 * <p>Scores are always percentages of {@code total_max_score}. A raw 12.5 is not
 * comparable to a raw 80, and a dashboard that adds them up is lying.</p>
 *
 * <p>A {@code null} class list means every class; a non-null one narrows the query to
 * those classes and binds them. Nothing else from the scope reaches the SQL, and no
 * value is ever interpolated.</p>
 */
@Repository
public class AnalyticsRepository {

    /** A finished simulation counts as passed from half of the paper upwards. */
    static final double PASS_THRESHOLD_PERCENT = 50.0;

    /** {@code s} is the simulations alias and {@code st} the statements one, throughout. */
    private static final String PERCENT = "s.final_score * 100.0 / st.total_max_score";

    private static final String FINISHED = "s.status = 'FINISHED'";

    private static final String SCORABLE =
            "s.final_score IS NOT NULL AND st.total_max_score IS NOT NULL AND st.total_max_score > 0";

    private static final String LIVE_STATEMENT = "s.deleted_at IS NULL AND st.deleted_at IS NULL";

    /**
     * A statement is answerable when every question has something to grade against: an
     * option marked correct, or the free-text model answer.
     */
    private static final String ANSWER_KEY_COMPLETE =
            "NOT EXISTS ("
            + " SELECT 1 FROM questions q"
            + " WHERE q.statement_id = st.id AND q.deleted_at IS NULL"
            + " AND NOT EXISTS (SELECT 1 FROM question_options qo"
            + " WHERE qo.question_id = q.id AND qo.deleted_at IS NULL AND qo.is_correct = TRUE)"
            + " AND (q.model_answer IS NULL OR btrim(q.model_answer) = ''))";

    /**
     * The finished, scorable simulations of every class, aggregated once.
     *
     * <p>A derived table rather than four more joins: the score needs
     * {@code statements} for its maximum, and pulling that table into the outer query
     * means referencing it before it appears in the FROM clause — or multiplying a
     * school's simulations by every class and student they pass through to get there.</p>
     */
    private static final String CLASS_SCORES =
            "LEFT JOIN ("
            + " SELECT e.class_id,"
            + "  COUNT(DISTINCT s.id) AS finished_simulations,"
            + "  AVG(" + PERCENT + ") AS average_score,"
            + "  AVG(s.time_spent_seconds) AS average_time"
            + " FROM enrollments e"
            // statements first: a percentage cannot be computed without the maximum, and
            // a join may only use what is already in the FROM clause.
            + " JOIN statements st ON st.deleted_at IS NULL"
            + "   AND st.total_max_score IS NOT NULL AND st.total_max_score > 0"
            + " JOIN simulations s ON s.statement_id = st.id AND s.account_id = e.account_id"
            + "   AND s.deleted_at IS NULL AND " + FINISHED + " AND s.final_score IS NOT NULL"
            + " WHERE e.deleted_at IS NULL AND e.status = 'ACTIVE'"
            + " GROUP BY e.class_id) sc ON sc.class_id = cl.id";

    /** The finished simulations of one statement, reused by the per-question counts. */
    private static final String FINISHED_OF_STATEMENT =
            "SELECT id FROM simulations WHERE statement_id = :statementId"
            + " AND deleted_at IS NULL AND status = 'FINISHED'";

    private final DatabaseClient databaseClient;

    public AnalyticsRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    // ── /overview ───────────────────────────────────────────────────────────

    public Flux<StatementCounts> statementCounts(List<Long> classIds) {
        return query(databaseClient.sql(sql(
                "SELECT COUNT(*) AS total,",
                "  COUNT(*) FILTER (WHERE st.visible = TRUE) AS published,",
                "  COUNT(*) FILTER (WHERE st.needs_review = TRUE) AS needing_review,",
                "  COUNT(*) FILTER (WHERE " + ANSWER_KEY_COMPLETE + ") AS missing_answer_key",
                "FROM statements st",
                "WHERE st.deleted_at IS NULL",
                in("st.class_id", classIds))),
                classIds,
                r -> new StatementCounts(
                        i(r, "total"), i(r, "published"), i(r, "needing_review"),
                        i(r, "missing_answer_key")));
    }

    public Flux<SimulationCounts> simulationCounts(List<Long> classIds) {
        return query(databaseClient.sql(sql(
                "SELECT COUNT(*) AS total,",
                "  COUNT(*) FILTER (WHERE s.status = 'FINISHED') AS finished,",
                "  COUNT(*) FILTER (WHERE s.status = 'IN_PROGRESS') AS in_progress,",
                "  COUNT(*) FILTER (WHERE s.status = 'CANCELLED') AS cancelled,",
                "  COUNT(DISTINCT s.account_id) AS unique_students",
                "FROM simulations s",
                "WHERE s.deleted_at IS NULL",
                seatedIn(classIds))),
                classIds,
                r -> new SimulationCounts(
                        i(r, "total"), i(r, "finished"), i(r, "in_progress"),
                        i(r, "cancelled"), i(r, "unique_students")));
    }

    /** The three averages of the scope, over finished and scorable simulations. */
    public Mono<ScoreSummary> scoreSummary(List<Long> classIds) {
        return query(databaseClient.sql(sql(
                "SELECT AVG(" + PERCENT + ") AS average_score,",
                "  AVG(CASE WHEN " + PERCENT + " >= :pass THEN 1.0 ELSE 0.0 END) AS pass_rate,",
                "  AVG(s.time_spent_seconds) AS average_time",
                "FROM simulations s",
                "JOIN statements st ON st.id = s.statement_id",
                "WHERE " + LIVE_STATEMENT + " AND " + FINISHED + " AND " + SCORABLE,
                seatedIn(classIds))),
                classIds,
                r -> new ScoreSummary(
                        percent(r.get("average_score")), percent(r.get("pass_rate")),
                        seconds(r.get("average_time"))))
                .next();
    }

    public Flux<PopulationCounts> populationCounts(List<Long> classIds) {
        return query(databaseClient.sql(sql(
                "SELECT COUNT(DISTINCT cl.institution_id) AS institutions,",
                "  COUNT(DISTINCT cl.id) AS classes,",
                "  COUNT(DISTINCT e.account_id) AS students,",
                "  COUNT(DISTINCT ta.teacher_id) AS teachers",
                "FROM classes cl",
                "LEFT JOIN enrollments e ON e.class_id = cl.id",
                "  AND e.deleted_at IS NULL AND e.status = 'ACTIVE'",
                "LEFT JOIN teaching_assignments ta ON ta.class_id = cl.id AND ta.deleted_at IS NULL",
                "WHERE cl.deleted_at IS NULL",
                in("cl.id", classIds))),
                classIds,
                r -> new PopulationCounts(
                        i(r, "institutions"), i(r, "classes"), i(r, "students"), i(r, "teachers")));
    }

    // ── /activity ───────────────────────────────────────────────────────────

    /** One row per day with something finished, between the two dates. */
    public Flux<DayPoint> activity(List<Long> classIds, LocalDateTime from, LocalDateTime to) {
        return query(databaseClient.sql(sql(
                "SELECT to_char(date_trunc('day', s.finished_at), 'YYYY-MM-DD') AS day,",
                "  COUNT(*) AS finished,",
                "  AVG(" + PERCENT + ") AS average_score",
                "FROM simulations s",
                "JOIN statements st ON st.id = s.statement_id",
                "WHERE " + LIVE_STATEMENT,
                "  AND " + FINISHED + " AND " + SCORABLE,
                "  AND s.finished_at >= :from AND s.finished_at < :to",
                seatedIn(classIds),
                // Grouped on the day, ordered by it: a chart is read left to right and the
                // database is free to return the days in any order it likes.
                "GROUP BY 1",
                "ORDER BY 1"))
                .bind("from", from)
                .bind("to", to),
                classIds,
                r -> new DayPoint(
                        String.valueOf(r.get("day")), i(r, "finished"),
                        percent(r.get("average_score"))));
    }

    // ── /institutions and /classes ──────────────────────────────────────────

    public Flux<InstitutionRow> institutionStats(List<Long> classIds) {
        return query(databaseClient.sql(sql(
                "SELECT i.id, i.code, i.name,",
                "  COUNT(DISTINCT co.id) AS courses,",
                "  COUNT(DISTINCT cl.id) AS classes,",
                "  COUNT(DISTINCT e.account_id) AS students,",
                "  COALESCE(SUM(sc.finished_simulations), 0) AS finished_simulations,",
                // Weighted by how many simulations each class contributed, so a class of
                // two does not swing the average of a school of four hundred.
                "  SUM(sc.average_score * sc.finished_simulations)",
                "    / NULLIF(SUM(sc.finished_simulations), 0) AS average_score,",
                "  SUM(sc.average_time * sc.finished_simulations)",
                "    / NULLIF(SUM(sc.finished_simulations), 0) AS average_time",
                "FROM institutions i",
                "LEFT JOIN classes cl ON cl.institution_id = i.id AND cl.deleted_at IS NULL",
                in("cl.id", classIds),
                "LEFT JOIN courses co ON co.institution_id = i.id AND co.deleted_at IS NULL",
                "LEFT JOIN enrollments e ON e.class_id = cl.id",
                "  AND e.deleted_at IS NULL AND e.status = 'ACTIVE'",
                CLASS_SCORES,
                "WHERE i.deleted_at IS NULL",
                "GROUP BY i.id, i.code, i.name",
                "ORDER BY i.name")),
                classIds,
                r -> new InstitutionRow(
                        n(r, "id"), (String) r.get("code"), (String) r.get("name"),
                        i(r, "courses"), i(r, "classes"), i(r, "students"),
                        i(r, "finished_simulations"),
                        percent(r.get("average_score")), seconds(r.get("average_time"))));
    }

    public Flux<ClassRow> classStats(List<Long> classIds) {
        return query(databaseClient.sql(sql(
                "SELECT cl.id, cl.code, cl.grade, co.name AS course, i.name AS institution,",
                "  COUNT(DISTINCT e.account_id) AS students,",
                "  COUNT(DISTINCT ta.teacher_id) AS teachers,",
                "  COUNT(DISTINCT stx.id) AS statements,",
                // One row per class in sc, so MAX is the value itself rather than an
                // aggregate over anything: PostgreSQL cannot see that sc is determined
                // by cl.id when the group is the rest of the class row.
                "  COALESCE(MAX(sc.finished_simulations), 0) AS finished_simulations,",
                "  MAX(sc.average_score) AS average_score,",
                "  MAX(sc.average_time) AS average_time",
                "FROM classes cl",
                "LEFT JOIN courses co ON co.id = cl.course_id AND co.deleted_at IS NULL",
                "LEFT JOIN institutions i ON i.id = cl.institution_id AND i.deleted_at IS NULL",
                "LEFT JOIN enrollments e ON e.class_id = cl.id",
                "  AND e.deleted_at IS NULL AND e.status = 'ACTIVE'",
                "LEFT JOIN teaching_assignments ta ON ta.class_id = cl.id AND ta.deleted_at IS NULL",
                "LEFT JOIN statements stx ON stx.class_id = cl.id AND stx.deleted_at IS NULL",
                CLASS_SCORES,
                "WHERE cl.deleted_at IS NULL",
                in("cl.id", classIds),
                "GROUP BY cl.id, cl.code, cl.grade, co.name, i.name",
                "ORDER BY co.name NULLS LAST, cl.code NULLS LAST")),
                classIds,
                r -> new ClassRow(
                        n(r, "id"), (String) r.get("code"), i(r, "grade"),
                        (String) r.get("course"), (String) r.get("institution"),
                        i(r, "students"), i(r, "teachers"), i(r, "statements"),
                        i(r, "finished_simulations"),
                        percent(r.get("average_score")), seconds(r.get("average_time"))));
    }

    // ── /statements ─────────────────────────────────────────────────────────

    public Flux<StatementRow> statementStats(List<Long> classIds, int limit) {
        return query(databaseClient.sql(sql(
                "SELECT st.id, st.title, st.exam_type, cl.code AS class_code,",
                "  sub.name AS subject, st.visible, st.needs_review,",
                "  (" + ANSWER_KEY_COMPLETE + ") AS answer_key_complete,",
                "  (SELECT COUNT(*) FROM questions q",
                "     WHERE q.statement_id = st.id AND q.deleted_at IS NULL) AS questions,",
                "  COUNT(DISTINCT s.id) AS finished_simulations,",
                "  COUNT(DISTINCT s.account_id) AS unique_students,",
                "  AVG(" + PERCENT + ") AS average_score,",
                "  AVG(CASE WHEN " + PERCENT + " >= :pass THEN 1.0 ELSE 0.0 END) AS pass_rate,",
                "  AVG(s.time_spent_seconds) AS average_time",
                "FROM statements st",
                "LEFT JOIN classes cl ON cl.id = st.class_id AND cl.deleted_at IS NULL",
                "LEFT JOIN subjects sub ON sub.id = st.subject_id AND sub.deleted_at IS NULL",
                "LEFT JOIN simulations s ON s.statement_id = st.id AND s.deleted_at IS NULL",
                "  AND " + FINISHED + " AND " + SCORABLE,
                "WHERE st.deleted_at IS NULL",
                in("st.class_id", classIds),
                "GROUP BY st.id, st.title, st.exam_type, cl.code, sub.name, st.visible, st.needs_review",
                "ORDER BY COUNT(DISTINCT s.id) DESC, st.id ASC",
                "LIMIT :limit"))
                .bind("limit", limit),
                classIds,
                r -> new StatementRow(
                        n(r, "id"), (String) r.get("title"), (String) r.get("exam_type"),
                        (String) r.get("class_code"), (String) r.get("subject"),
                        Boolean.TRUE.equals(r.get("visible")),
                        Boolean.TRUE.equals(r.get("needs_review")),
                        Boolean.TRUE.equals(r.get("answer_key_complete")),
                        i(r, "questions"), i(r, "finished_simulations"), i(r, "unique_students"),
                        percent(r.get("average_score")), percent(r.get("pass_rate")),
                        seconds(r.get("average_time"))));
    }

    /** Whether the statement exists and the caller may read it. */
    public Mono<Boolean> statementReadable(List<Long> classIds, Long statementId) {
        return query(databaseClient.sql(
                "SELECT COUNT(*) AS total FROM statements st"
                + " WHERE st.deleted_at IS NULL AND st.id = :statementId"
                + (classIds == null ? "" : " AND st.class_id IN (:classIds)")),
                classIds,
                r -> i(r, "total") > 0)
                .next();
    }

    /** Per-question attempts over the finished simulations that sat the statement. */
    public Flux<QuestionRow> questionStats(long statementId) {
        return query(databaseClient.sql(
                "SELECT q.id, q.number, q.text, q.question_type, q.max_score,"
                + "  (SELECT COUNT(*) FROM simulation_answers sa"
                + "     WHERE sa.question_id = q.id AND sa.deleted_at IS NULL"
                + "       AND sa.simulation_id IN (" + FINISHED_OF_STATEMENT + ")) AS attempts,"
                + "  (SELECT COUNT(*) FROM simulation_answers sa"
                + "     WHERE sa.question_id = q.id AND sa.deleted_at IS NULL"
                + "       AND (sa.selected_option_id IS NOT NULL"
                + "         OR (sa.answer_text IS NOT NULL AND btrim(sa.answer_text) <> ''))"
                + "       AND sa.simulation_id IN (" + FINISHED_OF_STATEMENT + ")) AS answered,"
                + "  (SELECT COUNT(*) FROM simulation_answers sa"
                + "     WHERE sa.question_id = q.id AND sa.deleted_at IS NULL"
                + "       AND sa.is_correct = TRUE"
                + "       AND sa.simulation_id IN (" + FINISHED_OF_STATEMENT + ")) AS correct,"
                + "  EXISTS (SELECT 1 FROM question_options qo"
                + "           WHERE qo.question_id = q.id AND qo.deleted_at IS NULL"
                + "             AND qo.is_correct = TRUE) AS auto_graded"
                + "FROM questions q"
                + "WHERE q.statement_id = :statementId AND q.deleted_at IS NULL"
                + "ORDER BY q.order_index NULLS LAST, q.number NULLS LAST")
                .bind("statementId", statementId),
                List.of(),
                r -> {
                    int attempts = i(r, "attempts");
                    return new QuestionRow(
                            n(r, "id"), i(r, "number"), (String) r.get("text"),
                            (String) r.get("question_type"),
                            r.get("max_score") == null
                                    ? null : ((Number) r.get("max_score")).doubleValue(),
                            attempts, i(r, "answered"), i(r, "correct"),
                            // Unanswered counts as wrong, but a question nobody can be
                            // graded on has no percentage worth reporting.
                            Boolean.TRUE.equals(r.get("auto_graded")) && attempts > 0
                                    ? round1(i(r, "correct") * 100.0 / attempts)
                                    : null);
                });
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private static String sql(String... lines) {
        return String.join("\n", lines);
    }

    private static String in(String column, List<Long> classIds) {
        return classIds == null ? "" : " AND " + column + " IN (:classIds)";
    }

    /** The simulations whose owner holds a seat in one of the scope's classes. */
    private static String seatedIn(List<Long> classIds) {
        if (classIds == null) {
            return "";
        }
        return " AND EXISTS (SELECT 1 FROM enrollments e"
                + " WHERE e.account_id = s.account_id"
                + " AND e.deleted_at IS NULL AND e.status = 'ACTIVE'"
                + " AND e.class_id IN (:classIds))";
    }

    private <T> Flux<T> query(DatabaseClient.GenericExecuteSpec spec, List<Long> classIds,
            Mapper<T> mapper) {
        DatabaseClient.GenericExecuteSpec bound = spec.bind("pass", PASS_THRESHOLD_PERCENT);
        if (classIds != null) {
            bound = bound.bind("classIds", classIds.toArray(Long[]::new));
        }
        return bound.map((row, metadata) -> mapper.map(row)).all();
    }

    private interface Mapper<T> {
        T map(Readable row);
    }

    public record StatementCounts(int total, int published, int needingReview,
            int missingAnswerKey) { }

    public record SimulationCounts(int total, int finished, int inProgress, int cancelled,
            int uniqueStudents) { }

    public record PopulationCounts(int institutions, int classes, int students, int teachers) { }

    public record ScoreSummary(Double averageScore, Double passRate, Double averageTime) { }

    public record DayPoint(String date, int finished, Double averageScore) { }

    public record InstitutionRow(long id, String code, String name, int courses, int classes,
            int students, int finishedSimulations, Double averageScore, Double averageTime) { }

    public record ClassRow(long id, String code, int grade, String course, String institution,
            int students, int teachers, int statements, int finishedSimulations,
            Double averageScore, Double averageTime) { }

    public record StatementRow(long id, String title, String examType, String classCode,
            String subject, boolean published, boolean needsReview, boolean answerKeyComplete,
            int questions, int finishedSimulations, int uniqueStudents, Double averageScore,
            Double passRate, Double averageTime) { }

    public record QuestionRow(long id, int number, String text, String questionType,
            Double maxScore, int attempts, int answered, int correct, Double correctPercent) { }

    // ── Reading ─────────────────────────────────────────────────────────────

    private static long n(Readable row, String column) {
        Object value = row.get(column);
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static int i(Readable row, String column) {
        Object value = row.get(column);
        return value == null ? 0 : ((Number) value).intValue();
    }

    /** A percentage to one decimal, or null where there is nothing to average. */
    private static Double percent(Object value) {
        return value == null ? null : round1(((Number) value).doubleValue());
    }

    /** An average time in whole seconds, or null when nobody recorded one. */
    private static Double seconds(Object value) {
        return value == null ? null : (double) Math.round(((Number) value).doubleValue());
    }

    static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}