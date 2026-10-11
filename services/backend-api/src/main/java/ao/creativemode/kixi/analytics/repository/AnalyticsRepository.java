package ao.creativemode.kixi.analytics.repository;

import ao.creativemode.kixi.analytics.dto.ActivityPoint;
import ao.creativemode.kixi.analytics.dto.ClassStats;
import ao.creativemode.kixi.analytics.dto.ClassStudentStats;
import ao.creativemode.kixi.analytics.dto.HardQuestion;
import ao.creativemode.kixi.analytics.dto.InstitutionStats;
import ao.creativemode.kixi.analytics.dto.Overview;
import ao.creativemode.kixi.analytics.dto.QuestionStats;
import ao.creativemode.kixi.analytics.dto.StatementStats;
import ao.creativemode.kixi.analytics.dto.WeeklyPoint;
import io.r2dbc.spi.Readable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Runs {@link AnalyticsSql} and maps the rows to the DTOs. Every query is one
 * aggregate statement: no query per row, so the cost does not grow with the
 * number of classes or statements on the page.
 */
@Repository
public class AnalyticsRepository {

    /** Below this average a student is listed as at risk. */
    public static final double AT_RISK_BELOW_PERCENT = AnalyticsSql.PASS_PERCENT;

    /** Whether a class or a statement exists, and whether the caller may see it. */
    public enum Access { NOT_FOUND, FORBIDDEN, ALLOWED }

    private final DatabaseClient database;

    public AnalyticsRepository(DatabaseClient database) {
        this.database = database;
    }

    public Mono<Overview> overview(AnalyticsScope scope) {
        return query(AnalyticsSql.overview(), scope, Map.of())
                .map((row, meta) -> new Overview(
                        scope.label(),
                        count(row, "institutions"),
                        count(row, "classes"),
                        count(row, "students"),
                        count(row, "teachers"),
                        new Overview.Statements(
                                count(row, "statements_total"),
                                count(row, "statements_published"),
                                count(row, "statements_review"),
                                count(row, "statements_missing_key")),
                        new Overview.Simulations(
                                count(row, "simulations_total"),
                                count(row, "simulations_finished"),
                                count(row, "simulations_in_progress"),
                                count(row, "simulations_cancelled"),
                                count(row, "simulations_students")),
                        decimal(row, "average_pct"),
                        decimal(row, "pass_pct"),
                        whole(row, "average_time")))
                .one();
    }

    public Flux<ActivityPoint> activity(AnalyticsScope scope, int days) {
        return query(AnalyticsSql.activity(), scope, Map.of("days", days))
                .map((row, meta) -> new ActivityPoint(
                        row.get("day", LocalDate.class).toString(),
                        count(row, "finished"),
                        decimal(row, "average_pct")))
                .all();
    }

    public Flux<InstitutionStats> institutions(AnalyticsScope scope) {
        return query(AnalyticsSql.institutions(), scope, Map.of())
                .map((row, meta) -> new InstitutionStats(
                        count(row, "id"),
                        row.get("code", String.class),
                        row.get("name", String.class),
                        count(row, "courses"),
                        count(row, "classes"),
                        count(row, "students"),
                        count(row, "finished"),
                        decimal(row, "average_pct"),
                        whole(row, "average_time")))
                .all();
    }

    public Flux<ClassStats> classes(AnalyticsScope scope) {
        return query(AnalyticsSql.classes(), scope, Map.of())
                .map((row, meta) -> new ClassStats(
                        count(row, "id"),
                        row.get("code", String.class),
                        integer(row, "grade"),
                        row.get("course", String.class),
                        row.get("institution", String.class),
                        count(row, "students"),
                        count(row, "teachers"),
                        count(row, "statements"),
                        count(row, "finished"),
                        decimal(row, "average_pct"),
                        whole(row, "average_time")))
                .all();
    }

    public Flux<StatementStats> statements(AnalyticsScope scope, int limit) {
        return query(AnalyticsSql.statements(), scope, Map.of("limit", limit))
                .map((row, meta) -> new StatementStats(
                        count(row, "id"),
                        row.get("title", String.class),
                        row.get("exam_type", String.class),
                        row.get("class_code", String.class),
                        row.get("subject", String.class),
                        flag(row, "published"),
                        flag(row, "needs_review"),
                        flag(row, "answer_key_complete"),
                        count(row, "questions"),
                        count(row, "finished"),
                        count(row, "students"),
                        decimal(row, "average_pct"),
                        decimal(row, "pass_pct"),
                        whole(row, "average_time")))
                .all();
    }

    public Mono<Access> statementAccess(AnalyticsScope scope, long statementId) {
        return access(AnalyticsSql.statementAccess(), scope, Map.of("statementId", statementId));
    }

    public Flux<QuestionStats> statementQuestions(AnalyticsScope scope, long statementId) {
        return query(AnalyticsSql.statementQuestions(), scope, Map.of("statementId", statementId))
                .map((row, meta) -> {
                    long attempts = count(row, "attempts");
                    long correct = count(row, "correct");
                    boolean autoGraded = flag(row, "auto_graded");
                    return new QuestionStats(
                            count(row, "id"),
                            integer(row, "number"),
                            row.get("text", String.class),
                            row.get("question_type", String.class),
                            decimal(row, "max_score"),
                            attempts,
                            count(row, "answered"),
                            correct,
                            autoGraded && attempts > 0 ? percent(correct, attempts) : null);
                })
                .all();
    }

    public Mono<Access> classAccess(AnalyticsScope scope, long classId) {
        return access(AnalyticsSql.classAccess(), scope, Map.of("classId", classId));
    }

    public Flux<ClassStudentStats> classStudents(AnalyticsScope scope, long classId) {
        return query(AnalyticsSql.classStudents(), scope, Map.of("classId", classId))
                .map((row, meta) -> {
                    long finished = count(row, "finished");
                    Double average = decimal(row, "average_pct");
                    OffsetDateTime last = row.get("last_finished_at", OffsetDateTime.class);
                    return new ClassStudentStats(
                            count(row, "account_id"),
                            row.get("name", String.class),
                            finished,
                            average,
                            last == null ? null : last.toString(),
                            studentStatus(finished, average));
                })
                .all();
    }

    public Flux<WeeklyPoint> classWeekly(AnalyticsScope scope, long classId, int weeks) {
        return query(AnalyticsSql.classWeekly(), scope, Map.of("classId", classId, "weeks", weeks))
                .map((row, meta) -> new WeeklyPoint(
                        row.get("week", LocalDate.class).toString(),
                        count(row, "finished"),
                        decimal(row, "average_pct")))
                .all();
    }

    public Flux<HardQuestion> classHardestQuestions(AnalyticsScope scope, long classId, int limit) {
        return query(AnalyticsSql.classHardestQuestions(), scope, Map.of("classId", classId, "limit", limit))
                .map((row, meta) -> new HardQuestion(
                        count(row, "id"),
                        integer(row, "number"),
                        row.get("text", String.class),
                        row.get("question_type", String.class),
                        decimal(row, "max_score"),
                        count(row, "statement_id"),
                        row.get("statement_title", String.class),
                        count(row, "attempts"),
                        count(row, "correct"),
                        decimal(row, "correct_pct")))
                .all();
    }

    /** {@code AT_RISK}, {@code NO_ACTIVITY} or {@code OK}; see {@link ClassStudentStats}. */
    static String studentStatus(long finished, Double average) {
        if (finished == 0 || average == null) return "NO_ACTIVITY";
        return average < AT_RISK_BELOW_PERCENT ? "AT_RISK" : "OK";
    }

    /** One decimal, like the SQL. */
    static double percent(long part, long whole) {
        return Math.round(part * 1000.0 / whole) / 10.0;
    }

    private DatabaseClient.GenericExecuteSpec query(String sql, AnalyticsScope scope, Map<String, Object> binds) {
        Map<String, Object> all = new java.util.LinkedHashMap<>();
        all.put("accountId", scope.accountId());
        all.put("admin", scope.admin());
        all.put("wide", scope.wide());
        all.put("filterInstitution", scope.institutionId());
        all.put("filterClass", scope.classId());
        all.putAll(binds);
        DatabaseClient.GenericExecuteSpec spec = database.sql(sql);
        for (Map.Entry<String, Object> bind : all.entrySet()) {
            // Only the binds the statement names: not every query uses :wide.
            if (sql.contains(":" + bind.getKey())) {
                spec = spec.bind(bind.getKey(), bind.getValue());
            }
        }
        return spec;
    }

    private Mono<Access> access(String sql, AnalyticsScope scope, Map<String, Object> binds) {
        return query(sql, scope, binds)
                .map((row, meta) -> switch (integer(row, "access")) {
                    case 2 -> Access.ALLOWED;
                    case 1 -> Access.FORBIDDEN;
                    default -> Access.NOT_FOUND;
                })
                .one()
                .defaultIfEmpty(Access.NOT_FOUND);
    }

    private static long count(Readable row, String column) {
        Number value = row.get(column, Number.class);
        return value == null ? 0L : value.longValue();
    }

    private static int integer(Readable row, String column) {
        Number value = row.get(column, Number.class);
        return value == null ? 0 : value.intValue();
    }

    private static Double decimal(Readable row, String column) {
        BigDecimal value = row.get(column, BigDecimal.class);
        return value == null ? null : value.doubleValue();
    }

    private static Long whole(Readable row, String column) {
        Number value = row.get(column, Number.class);
        return value == null ? null : value.longValue();
    }

    private static boolean flag(Readable row, String column) {
        return Boolean.TRUE.equals(row.get(column, Boolean.class));
    }
}
