package ao.creativemode.kixi.analytics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.analytics.dto.ClassStats;
import ao.creativemode.kixi.analytics.dto.ClassStudentStats;
import ao.creativemode.kixi.analytics.dto.HardQuestion;
import ao.creativemode.kixi.analytics.dto.Overview;
import ao.creativemode.kixi.analytics.dto.QuestionStats;
import ao.creativemode.kixi.analytics.dto.StatementStats;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.Access;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * BE-15 against a database migrated by Flyway: teacher A never sees the numbers of
 * teacher B's class, not even of another subject inside A's own class.
 *
 * <p>Two teachers in one school. A teaches 12B Redes. B teaches 11A SO, and also
 * gives a SO paper to A's 12B, which A must not see. Ids from 920001 so the rows do
 * not collide with other suites sharing the database.</p>
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
@EnabledIfEnvironmentVariable(named = "KIXI_POSTGRES_TESTS", matches = "true")
class AnalyticsPostgresTest {

    private static final long TEACHER_A = 920001L;
    private static final long TEACHER_B = 920002L;
    private static final long ADMIN = 920003L;
    private static final long CLASS_A = 920001L;
    private static final long CLASS_B = 920002L;
    private static final long REDES_A = 920001L;
    private static final long SO_IN_CLASS_A = 920002L;
    private static final long SO_B = 920003L;

    @Autowired AnalyticsRepository analytics;
    @Autowired DatabaseClient database;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        String host = env("KIXI_POSTGRES_HOST", "localhost");
        String port = env("KIXI_POSTGRES_PORT", "5433");
        String name = env("KIXI_POSTGRES_DATABASE", "kixi_exam_room_it");
        String user = env("KIXI_POSTGRES_USER", "kixi");
        String password = env("KIXI_POSTGRES_PASSWORD", "kixi_secret");
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + host + ":" + port + "/" + name);
        registry.add("spring.r2dbc.username", () -> user);
        registry.add("spring.r2dbc.password", () -> password);
        registry.add("spring.flyway.url", () -> "jdbc:postgresql://" + host + ":" + port + "/" + name);
        registry.add("spring.flyway.user", () -> user);
        registry.add("spring.flyway.password", () -> password);
    }

    @BeforeEach
    void data() {
        for (String cleanup : List.of(
                "DELETE FROM simulation_answers WHERE simulation_id BETWEEN 920000 AND 929999",
                "DELETE FROM simulations WHERE id BETWEEN 920000 AND 929999",
                "DELETE FROM question_options WHERE id BETWEEN 920000 AND 929999",
                "DELETE FROM questions WHERE id BETWEEN 920000 AND 929999",
                "DELETE FROM statements WHERE id BETWEEN 920000 AND 929999",
                "DELETE FROM enrollments WHERE account_id BETWEEN 920000 AND 929999",
                "DELETE FROM teaching_assignments WHERE teacher_id BETWEEN 920000 AND 929999",
                "DELETE FROM teachers WHERE id BETWEEN 920000 AND 929999",
                "DELETE FROM account_roles WHERE account_id BETWEEN 920000 AND 929999",
                "DELETE FROM accounts WHERE id BETWEEN 920000 AND 929999",
                "DELETE FROM classes WHERE id BETWEEN 920000 AND 929999",
                "DELETE FROM courses WHERE id BETWEEN 920000 AND 929999",
                "DELETE FROM subjects WHERE id BETWEEN 920000 AND 929999",
                "DELETE FROM school_years WHERE id BETWEEN 920000 AND 929999",
                "DELETE FROM institutions WHERE id BETWEEN 920000 AND 929999")) {
            sql(cleanup);
        }
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) "
                + "VALUES (920001, 'IT920', 'Escola de teste', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) "
                + "VALUES (920001, 2091, 2092, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) "
                + "VALUES (920001, 'C920', 'Informática', 920001, now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES "
                + "(920001, '12B', 12, 920001, 920001, 920001, now(), now()), "
                + "(920002, '11A', 11, 920001, 920001, 920001, now(), now())");
        sql("INSERT INTO subjects (id, code, name, created_at, updated_at) VALUES "
                + "(920001, 'R920', 'Redes', now(), now()), (920002, 'S920', 'Sistemas Operativos', now(), now())");
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, active, created_at, updated_at) "
                + "SELECT i, 'an' || i, 'an' || i || '@kixi.test', 'x', true, true, now(), now() "
                + "FROM generate_series(920001, 920003) i");
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, active, created_at, updated_at) "
                + "SELECT i, 'an' || i, 'an' || i || '@kixi.test', 'x', true, true, now(), now() "
                + "FROM generate_series(920101, 920104) i");
        sql("INSERT INTO account_roles (account_id, role_id, created_at) "
                + "SELECT i, (SELECT id FROM roles WHERE name = 'STUDENT'), now() FROM generate_series(920101, 920104) i");
        sql("INSERT INTO teachers (id, account_id, first_name, last_name, created_at, updated_at) VALUES "
                + "(920001, 920001, 'Helena', 'Teste', now(), now()), (920002, 920002, 'Carlos', 'Teste', now(), now())");
        sql("INSERT INTO teaching_assignments (teacher_id, class_id, subject_id, school_year_id, created_at) VALUES "
                + "(920001, 920001, 920001, 920001, now()), "
                + "(920002, 920002, 920002, 920001, now()), "
                + "(920002, 920001, 920002, 920001, now())");
        sql("INSERT INTO enrollments (account_id, class_id, school_year_id, status, created_at, updated_at) VALUES "
                + "(920101, 920001, 920001, 'ACTIVE', now(), now()), "
                + "(920102, 920001, 920001, 'ACTIVE', now(), now()), "
                + "(920103, 920002, 920001, 'ACTIVE', now(), now()), "
                + "(920104, 920002, 920001, 'ACTIVE', now(), now())");
        sql("INSERT INTO statements (id, exam_type, title, total_max_score, subject_id, class_id, institution_id, "
                + "visible, needs_review, source, created_at, updated_at) VALUES "
                + "(920001, 'P1', 'Redes 12B', 20, 920001, 920001, 920001, true, false, 'MANUAL', now(), now()), "
                + "(920002, 'P1', 'SO 12B', 20, 920002, 920001, 920001, true, false, 'MANUAL', now(), now()), "
                + "(920003, 'P1', 'SO 11A', 20, 920002, 920002, 920001, true, false, 'MANUAL', now(), now())");
        sql("INSERT INTO questions (id, statement_id, number, text, question_type, max_score, page_index, "
                + "needs_review, created_at, updated_at) VALUES "
                + "(920001, 920001, 1, 'Broadcast?', 'MULTIPLE_CHOICE', 10, 0, false, now(), now()), "
                + "(920002, 920001, 2, 'VLAN?', 'OPEN', 10, 0, false, now(), now())");
        sql("INSERT INTO question_options (id, question_id, option_label, option_text, is_correct, order_index, "
                + "created_at, updated_at) VALUES "
                + "(920001, 920001, 'A', '.31', false, 0, now(), now()), "
                + "(920002, 920001, 'B', '.63', true, 1, now(), now())");
        sql("INSERT INTO simulations (id, account_id, statement_id, started_at, finished_at, time_spent_seconds, "
                + "final_score, status, created_at, updated_at) VALUES "
                + "(920001, 920101, 920001, now(), now(), 1800, 16, 'FINISHED', now(), now()), "
                + "(920002, 920102, 920001, now(), now(), 2400, 8, 'FINISHED', now(), now()), "
                + "(920003, 920101, 920002, now(), now(), 600, 4, 'FINISHED', now(), now()), "
                + "(920004, 920103, 920003, now(), now(), 900, 18, 'FINISHED', now(), now())");
        sql("INSERT INTO simulation_answers (simulation_id, question_id, selected_option_id, is_correct, "
                + "created_at, updated_at) VALUES "
                + "(920001, 920001, 920002, true, now(), now()), "
                + "(920002, 920001, 920001, false, now(), now())");
    }

    @Test
    void teacherASeesOnlyTheirClassAndSubject() {
        AnalyticsScope a = new AnalyticsScope(TEACHER_A, false);

        Overview overview = analytics.overview(a).block(Duration.ofSeconds(10));
        assertThat(overview.scope()).isEqualTo("TEACHER");
        assertThat(overview.classes()).isEqualTo(1);
        assertThat(overview.students()).isEqualTo(2);
        assertThat(overview.statements().total()).isEqualTo(1);
        assertThat(overview.simulations().finished()).isEqualTo(2);
        // 16/20 and 8/20; the SO paper in the same class (4/20) is B's, not A's
        assertThat(overview.averageScorePercent()).isEqualTo(60.0);
        assertThat(overview.passRatePercent()).isEqualTo(50.0);

        List<StatementStats> statements = analytics.statements(a, 50).collectList().block(Duration.ofSeconds(10));
        assertThat(statements).extracting(StatementStats::id).containsExactly(REDES_A);

        List<ClassStats> classes = analytics.classes(a).collectList().block(Duration.ofSeconds(10));
        assertThat(classes).extracting(ClassStats::id).containsExactly(CLASS_A);
    }

    @Test
    void teacherBNeverSeesTeacherAsSubject() {
        AnalyticsScope b = new AnalyticsScope(TEACHER_B, false);

        List<StatementStats> statements = analytics.statements(b, 50).collectList().block(Duration.ofSeconds(10));
        assertThat(statements).extracting(StatementStats::id).containsExactlyInAnyOrder(SO_IN_CLASS_A, SO_B);
        assertThat(analytics.statementAccess(b, REDES_A).block(Duration.ofSeconds(10))).isEqualTo(Access.FORBIDDEN);
        assertThat(analytics.statementAccess(b, 929999L).block(Duration.ofSeconds(10))).isEqualTo(Access.NOT_FOUND);
    }

    @Test
    void aClassOfAnotherTeacherIsForbiddenAndTheStudentsListIsScoped() {
        AnalyticsScope a = new AnalyticsScope(TEACHER_A, false);
        assertThat(analytics.classAccess(a, CLASS_B).block(Duration.ofSeconds(10))).isEqualTo(Access.FORBIDDEN);
        assertThat(analytics.classAccess(a, CLASS_A).block(Duration.ofSeconds(10))).isEqualTo(Access.ALLOWED);

        List<ClassStudentStats> students = analytics.classStudents(a, CLASS_A).collectList().block(Duration.ofSeconds(10));
        assertThat(students).extracting(ClassStudentStats::accountId).containsExactly(920102L, 920101L);
        // 920101 also failed B's SO paper (20%), which must not drag A's view of them down
        assertThat(students.get(1).averageScorePercent()).isEqualTo(80.0);
        assertThat(students.get(0).status()).isEqualTo("AT_RISK");
    }

    @Test
    void theMostMissedQuestionComesFirstWithItsPercentage() {
        AnalyticsScope a = new AnalyticsScope(TEACHER_A, false);

        List<HardQuestion> hardest = analytics.classHardestQuestions(a, CLASS_A, 10).collectList()
                .block(Duration.ofSeconds(10));
        assertThat(hardest).hasSize(1);
        assertThat(hardest.get(0).correctPercent()).isEqualTo(50.0);

        List<QuestionStats> questions = analytics.statementQuestions(a, REDES_A).collectList()
                .block(Duration.ofSeconds(10));
        assertThat(questions).extracting(QuestionStats::correctPercent).containsExactly(50.0, null);
        assertThat(questions).extracting(QuestionStats::attempts).containsExactly(2L, 2L);
    }

    @Test
    void anAdminSeesEveryStatementOfTheSchool() {
        AnalyticsScope admin = new AnalyticsScope(ADMIN, true);
        List<StatementStats> statements = analytics.statements(admin, 200).collectList().block(Duration.ofSeconds(10));
        assertThat(statements).extracting(StatementStats::id).contains(REDES_A, SO_IN_CLASS_A, SO_B);
        assertThat(analytics.overview(admin).block(Duration.ofSeconds(10)).scope()).isEqualTo("ADMIN");
        assertThat(analytics.activity(admin, 7).collectList().block(Duration.ofSeconds(10))).hasSize(7);
    }

    @Test
    void aFilterNarrowsButNeverWidensTheScope() {
        AnalyticsScope admin = new AnalyticsScope(ADMIN, true).filtered(null, CLASS_B);
        assertThat(analytics.statements(admin, 50).collectList().block(Duration.ofSeconds(10)))
                .extracting(StatementStats::id).containsExactly(SO_B);
        assertThat(analytics.overview(admin).block(Duration.ofSeconds(10)).students()).isEqualTo(2);

        // Teacher A asking for B's class gets nothing, not B's numbers.
        AnalyticsScope a = new AnalyticsScope(TEACHER_A, false).filtered(null, CLASS_B);
        Overview overview = analytics.overview(a).block(Duration.ofSeconds(10));
        assertThat(overview.statements().total()).isZero();
        assertThat(overview.averageScorePercent()).isNull();
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(10));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
