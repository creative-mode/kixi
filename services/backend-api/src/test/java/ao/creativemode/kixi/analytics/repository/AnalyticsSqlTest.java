package ao.creativemode.kixi.analytics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The analytics SQL without a database: scope, binds and the text-block traps. */
class AnalyticsSqlTest {

    private static final List<String> ALL = List.of(
            AnalyticsSql.overview(),
            AnalyticsSql.activity(),
            AnalyticsSql.institutions(),
            AnalyticsSql.classes(),
            AnalyticsSql.statements(),
            AnalyticsSql.statementAccess(),
            AnalyticsSql.statementQuestions(),
            AnalyticsSql.classAccess(),
            AnalyticsSql.classStudents(),
            AnalyticsSql.classWeekly(),
            AnalyticsSql.classHardestQuestions());

    @Test
    void everyQueryIsNarrowedByTheCallersScope() {
        for (String sql : ALL) {
            assertThat(sql).startsWith(AnalyticsSql.SCOPE);
            assertThat(sql).contains(":accountId").contains(":admin");
        }
    }

    @Test
    void aTeacherSeesAStatementOnlyThroughTheExactClassAndSubjectPair() {
        // The same pair requireTeaches demands. Class OR subject let a teacher see a
        // colleague's subject in their own class.
        assertThat(AnalyticsSql.SCOPE)
                .contains("p.class_id = s.class_id AND p.subject_id = s.subject_id")
                .doesNotContain("p.class_id = s.class_id OR");
    }

    @Test
    void deletedRowsNeverCount() {
        assertThat(AnalyticsSql.SCOPE)
                .contains("ta.deleted_at IS NULL")
                .contains("t.deleted_at IS NULL")
                .contains("c.deleted_at IS NULL")
                .contains("s.deleted_at IS NULL")
                .contains("sim.deleted_at IS NULL");
    }

    @Test
    void aCancelledEnrollmentOrADeletedStudentNeverCounts() {
        for (String sql : List.of(AnalyticsSql.overview(), AnalyticsSql.institutions(),
                AnalyticsSql.classes(), AnalyticsSql.classStudents())) {
            assertThat(sql).contains("e.status = 'ACTIVE'");
        }
        assertThat(AnalyticsSql.SCOPE).contains("sa.deleted_at IS NULL");
        assertThat(AnalyticsSql.classStudents()).contains("a.deleted_at IS NULL");
    }

    @Test
    void concatenatedPiecesKeepTheirSpaces() {
        // A text block drops the space before its closing quotes: "WHERE " + "EXISTS"
        // came out as WHEREEXISTS once already.
        for (String sql : ALL) {
            assertThat(sql).doesNotContain("WHEREEXISTS").doesNotContain("NOTEXISTS");
        }
        assertThat(AnalyticsSql.overview()).contains("pct >= " + AnalyticsSql.PASS_PERCENT);
        assertThat(AnalyticsSql.statements()).contains("sc.pct >= " + AnalyticsSql.PASS_PERCENT);
    }

    @Test
    void eachQueryUsesOnlyTheBindsTheRepositoryProvides() {
        List<String> base = List.of("accountId", "admin", "filterInstitution", "filterClass");
        assertThat(binds(AnalyticsSql.overview())).containsExactlyInAnyOrderElementsOf(with(base, "wide"));
        assertThat(binds(AnalyticsSql.institutions())).containsExactlyInAnyOrderElementsOf(with(base, "wide"));
        assertThat(binds(AnalyticsSql.activity())).containsExactlyInAnyOrderElementsOf(with(base, "days"));
        assertThat(binds(AnalyticsSql.statements())).containsExactlyInAnyOrderElementsOf(with(base, "limit"));
        assertThat(binds(AnalyticsSql.statementQuestions())).containsExactlyInAnyOrderElementsOf(with(base, "statementId"));
        assertThat(binds(AnalyticsSql.classWeekly())).containsExactlyInAnyOrderElementsOf(with(base, "classId", "weeks"));
        assertThat(binds(AnalyticsSql.classHardestQuestions()))
                .containsExactlyInAnyOrderElementsOf(with(base, "classId", "limit"));
    }

    @Test
    void filtersOnlyNarrowTheScope() {
        // The filters sit next to the role check, never instead of it.
        assertThat(AnalyticsSql.SCOPE)
                .contains("(:admin OR c.id IN (SELECT class_id FROM scope_pairs))\n"
                        + "      AND (CAST(:filterInstitution AS bigint) = 0");
        assertThat(new AnalyticsScope(1, true).wide()).isTrue();
        assertThat(new AnalyticsScope(1, true).filtered(4L, null).wide()).isFalse();
        assertThat(new AnalyticsScope(1, false).wide()).isFalse();
    }

    private static List<String> with(List<String> base, String... extra) {
        List<String> out = new java.util.ArrayList<>(base);
        out.addAll(List.of(extra));
        return out;
    }

    @Test
    void studentStatusFollowsTheAverage() {
        assertThat(AnalyticsRepository.studentStatus(0, null)).isEqualTo("NO_ACTIVITY");
        assertThat(AnalyticsRepository.studentStatus(2, 49.9)).isEqualTo("AT_RISK");
        assertThat(AnalyticsRepository.studentStatus(2, 50.0)).isEqualTo("OK");
        assertThat(AnalyticsRepository.percent(2, 3)).isEqualTo(66.7);
    }

    /** Named binds, without the {@code ::} casts. */
    private static List<String> binds(String sql) {
        Matcher m = Pattern.compile("(?<!:):([a-zA-Z]+)").matcher(sql);
        return m.results().map(r -> r.group(1)).distinct().toList();
    }
}
