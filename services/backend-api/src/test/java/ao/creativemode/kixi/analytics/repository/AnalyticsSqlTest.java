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
        assertThat(binds(AnalyticsSql.overview())).containsExactlyInAnyOrder("accountId", "admin");
        assertThat(binds(AnalyticsSql.activity())).containsExactlyInAnyOrder("accountId", "admin", "days");
        assertThat(binds(AnalyticsSql.statements())).containsExactlyInAnyOrder("accountId", "admin", "limit");
        assertThat(binds(AnalyticsSql.statementQuestions())).containsExactlyInAnyOrder("accountId", "admin", "statementId");
        assertThat(binds(AnalyticsSql.classWeekly())).containsExactlyInAnyOrder("accountId", "admin", "classId", "weeks");
        assertThat(binds(AnalyticsSql.classHardestQuestions()))
                .containsExactlyInAnyOrder("accountId", "admin", "classId", "limit");
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
