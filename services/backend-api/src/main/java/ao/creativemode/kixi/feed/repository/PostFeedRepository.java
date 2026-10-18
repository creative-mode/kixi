package ao.creativemode.kixi.feed.repository;

import ao.creativemode.kixi.feed.model.Post;
import ao.creativemode.kixi.shared.service.FeedScopeAuthorizer.FeedScope;
import ao.creativemode.kixi.shared.service.FeedScopeAuthorizer;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The feed listing and its moderation queue, both scoped by {@link FeedScope}.
 *
 * <p>A derived Spring Data query cannot bind the caller's authorized
 * class/course/school-year ids as a SQL array, so this reads the posts table
 * directly, the way {@code LeaderboardRepository} does for the same reason.
 * The SQL is built by a pure static method so the scoping can be checked
 * without a database.</p>
 */
@Repository
public class PostFeedRepository {

    private final DatabaseClient databaseClient;

    public PostFeedRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /** The feed a caller may see, newest first, optionally narrowed to one class/course/year inside their scope. */
    public Flux<Post> findFeed(FeedScope scope, Long schoolYearId, Long courseId, Long classId, int limit, long offset) {
        Sql sql = feedSql(scope, schoolYearId, courseId, classId, limit, offset);
        return bindAll(databaseClient.sql(sql.statement()), sql.binds())
                .map((row, metadata) -> rowToPost(row))
                .all();
    }

    /** How many posts a {@link #findFeed} call with the same filters would page over. */
    public Mono<Long> countFeed(FeedScope scope, Long schoolYearId, Long courseId, Long classId) {
        Sql sql = countSql(scope, schoolYearId, courseId, classId);
        return bindAll(databaseClient.sql(sql.statement()), sql.binds())
                .map((row, metadata) -> ((Number) row.get("total")).longValue())
                .one()
                .defaultIfEmpty(0L);
    }

    /** Posts with at least one report, inside the classes a moderator may act on. */
    public Flux<ReportedPost> findReported(Set<Long> classIds) {
        String statement = """
                SELECT p.id, p.author_account_id, p.class_id, p.content, p.is_hidden, p.created_at,
                       COUNT(r.id) AS report_count
                  FROM posts p
                  JOIN post_reports r ON r.post_id = p.id
                 WHERE p.deleted_at IS NULL AND p.class_id = ANY(:classIds)
                 GROUP BY p.id, p.author_account_id, p.class_id, p.content, p.is_hidden, p.created_at
                 ORDER BY report_count DESC, p.created_at DESC
                """;
        return databaseClient.sql(statement)
                .bind("classIds", classIds.toArray(Long[]::new))
                .map((row, metadata) -> rowToReportedPost(row))
                .all();
    }

    /** Every reported post, for an ADMIN who moderates across every school. */
    public Flux<ReportedPost> findAllReported() {
        String statement = """
                SELECT p.id, p.author_account_id, p.class_id, p.content, p.is_hidden, p.created_at,
                       COUNT(r.id) AS report_count
                  FROM posts p
                  JOIN post_reports r ON r.post_id = p.id
                 WHERE p.deleted_at IS NULL
                 GROUP BY p.id, p.author_account_id, p.class_id, p.content, p.is_hidden, p.created_at
                 ORDER BY report_count DESC, p.created_at DESC
                """;
        return databaseClient.sql(statement)
                .map((row, metadata) -> rowToReportedPost(row))
                .all();
    }

    private static ReportedPost rowToReportedPost(io.r2dbc.spi.Row row) {
        return new ReportedPost(
                row.get("id", Long.class),
                row.get("author_account_id", Long.class),
                row.get("class_id", Long.class),
                row.get("content", String.class),
                Boolean.TRUE.equals(row.get("is_hidden", Boolean.class)),
                ((Number) row.get("report_count")).longValue(),
                row.get("created_at", java.time.LocalDateTime.class));
    }

    public record ReportedPost(
            Long postId, Long authorAccountId, Long classId, String content,
            boolean isHidden, long reportCount, java.time.LocalDateTime createdAt) { }

    static Sql feedSql(FeedScope scope, Long schoolYearId, Long courseId, Long classId, int limit, long offset) {
        Map<String, Object> binds = new LinkedHashMap<>();
        String where = scopeWhere(scope, schoolYearId, courseId, classId, binds);
        binds.put("limit", limit);
        binds.put("offset", offset);
        String statement = "SELECT * FROM posts WHERE " + where
                + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset";
        return new Sql(statement, binds);
    }

    static Sql countSql(FeedScope scope, Long schoolYearId, Long courseId, Long classId) {
        Map<String, Object> binds = new LinkedHashMap<>();
        String where = scopeWhere(scope, schoolYearId, courseId, classId, binds);
        return new Sql("SELECT COUNT(*) AS total FROM posts WHERE " + where, binds);
    }

    /**
     * Every clause here is mandatory, not optional like {@code LeaderboardRepository}'s
     * filters: the scope sets come from the server, never the caller, and an empty
     * scope must yield zero rows rather than an unfiltered table.
     */
    private static String scopeWhere(
            FeedScope scope, Long schoolYearId, Long courseId, Long classId, Map<String, Object> binds) {
        List<String> where = new ArrayList<>();
        where.add("is_hidden = false");
        where.add("deleted_at IS NULL");
        where.add("institution_id = ANY(:institutionIds)");
        // Course and school year are matched together with their institution, never
        // from flat sets: a course id held in school A must not open school B's posts.
        where.add("(class_id = ANY(:classIds) OR EXISTS (SELECT 1 FROM unnest("
                + ":placementInstitutionIds::bigint[], :placementCourseIds::bigint[], :placementSchoolYearIds::bigint[]) "
                + "AS held(institution_id, course_id, school_year_id) "
                + "WHERE held.institution_id = posts.institution_id "
                + "AND (held.course_id = posts.course_id OR held.school_year_id = posts.school_year_id)))");
        binds.put("institutionIds", toArray(scope.institutionIds()));
        binds.put("classIds", toArray(scope.classIds()));
        List<FeedScopeAuthorizer.Placement> placements = List.copyOf(scope.placements());
        binds.put("placementInstitutionIds", placements.stream().map(FeedScopeAuthorizer.Placement::institutionId).toArray(Long[]::new));
        binds.put("placementCourseIds", placements.stream().map(FeedScopeAuthorizer.Placement::courseId).toArray(Long[]::new));
        binds.put("placementSchoolYearIds", placements.stream().map(FeedScopeAuthorizer.Placement::schoolYearId).toArray(Long[]::new));

        // Optional narrowing the caller asked for. The service has already checked
        // each of these against the scope above, so this can only narrow, never widen.
        if (classId != null) {
            where.add("class_id = :classId");
            binds.put("classId", classId);
        }
        if (courseId != null) {
            where.add("course_id = :courseId");
            binds.put("courseId", courseId);
        }
        if (schoolYearId != null) {
            where.add("school_year_id = :schoolYearId");
            binds.put("schoolYearId", schoolYearId);
        }
        return String.join(" AND ", where);
    }

    private static Long[] toArray(Set<Long> ids) {
        // An empty array still binds a valid, typed Postgres array, and ANY() over
        // it is simply never true - the scope-less caller sees nothing, no error.
        return ids.toArray(Long[]::new);
    }

    static Post rowToPost(io.r2dbc.spi.Row row) {
        return Post.builder()
                .id(row.get("id", Long.class))
                .authorAccountId(row.get("author_account_id", Long.class))
                .institutionId(row.get("institution_id", Long.class))
                .schoolYearId(row.get("school_year_id", Long.class))
                .courseId(row.get("course_id", Long.class))
                .classId(row.get("class_id", Long.class))
                .type(row.get("type", String.class))
                .title(row.get("title", String.class))
                .content(row.get("content", String.class))
                .blocks(row.get("blocks", String.class))
                .simulationId(row.get("simulation_id", Long.class))
                .statementTitle(row.get("statement_title", String.class))
                .schoolName(row.get("school_name", String.class))
                .finalScore(row.get("final_score", Double.class))
                .maxScore(row.get("max_score", Double.class))
                .correctAnswers(row.get("correct_answers", Integer.class))
                .totalQuestions(row.get("total_questions", Integer.class))
                .timeSpentSeconds(row.get("time_spent_seconds", Integer.class))
                .isAutoGenerated(row.get("is_auto_generated", Boolean.class))
                .isHidden(row.get("is_hidden", Boolean.class))
                .hiddenByAccountId(row.get("hidden_by_account_id", Long.class))
                .hiddenAt(row.get("hidden_at", java.time.LocalDateTime.class))
                .createdAt(row.get("created_at", java.time.LocalDateTime.class))
                .updatedAt(row.get("updated_at", java.time.LocalDateTime.class))
                .deletedAt(row.get("deleted_at", java.time.LocalDateTime.class))
                .build();
    }

    private static DatabaseClient.GenericExecuteSpec bindAll(
            DatabaseClient.GenericExecuteSpec spec, Map<String, Object> binds) {
        for (Map.Entry<String, Object> bind : binds.entrySet()) {
            spec = spec.bind(bind.getKey(), bind.getValue());
        }
        return spec;
    }

    record Sql(String statement, Map<String, Object> binds) { }
}
