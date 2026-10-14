package ao.creativemode.kixi.issue122;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import ao.creativemode.kixi.identity.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Analytics, gamification and ranking after a real submission is corrected (#122). */
@SpringBootTest(properties = {
        "spring.r2dbc.pool.max-size=2",
        "spring.r2dbc.pool.initial-size=0",
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
@EnabledIfEnvironmentVariable(named = "KIXI_POSTGRES_TESTS", matches = "true")
class AnalyticsLeaderboardPostgresIntegrationTest {
    private static final long TEACHER = 122201L;
    private static final long STUDENT = 122202L;
    private static final long OTHER_STUDENT = 122203L;
    private static final long TEACHER_ROW = 122201L;
    private static final long INSTITUTION = 122201L;
    private static final long OTHER_INSTITUTION = 122202L;
    private static final long YEAR = 122201L;
    private static final long COURSE = 122201L;
    private static final long OTHER_COURSE = 122202L;
    private static final long CLASS = 122201L;
    private static final long OTHER_CLASS = 122202L;
    private static final long SUBJECT = 122201L;
    private static final long STATEMENT = 122201L;
    private static final long QUESTION = 122201L;

    @Autowired
    private ApplicationContext context;
    @Autowired
    private DatabaseClient database;
    @Autowired
    private JwtService jwtService;

    private WebTestClient client;
    private String teacherToken;
    private String studentToken;
    private String otherStudentToken;

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
    void fixture() {
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity())
                .configureClient().responseTimeout(Duration.ofSeconds(20)).build();
        sql("DELETE FROM simulation_answers WHERE simulation_id IN (SELECT id FROM simulations WHERE account_id IN ("
                + TEACHER + "," + STUDENT + "," + OTHER_STUDENT + "))");
        sql("DELETE FROM simulations WHERE account_id IN (" + TEACHER + "," + STUDENT + "," + OTHER_STUDENT + ")");
        sql("DELETE FROM leaderboard_consents WHERE account_id IN (" + STUDENT + "," + OTHER_STUDENT + ")");
        sql("DELETE FROM enrollments WHERE account_id IN (" + TEACHER + "," + STUDENT + "," + OTHER_STUDENT + ")");
        sql("DELETE FROM teaching_assignments WHERE teacher_id = " + TEACHER_ROW);
        sql("DELETE FROM teachers WHERE id = " + TEACHER_ROW);
        sql("DELETE FROM account_roles WHERE account_id IN (" + TEACHER + "," + STUDENT + "," + OTHER_STUDENT + ")");
        sql("DELETE FROM users WHERE account_id IN (" + TEACHER + "," + STUDENT + "," + OTHER_STUDENT + ")");
        sql("DELETE FROM accounts WHERE id IN (" + TEACHER + "," + STUDENT + "," + OTHER_STUDENT + ")");
        sql("DELETE FROM questions WHERE id = " + QUESTION);
        sql("DELETE FROM statements WHERE id = " + STATEMENT);
        sql("DELETE FROM classes WHERE id IN (" + CLASS + "," + OTHER_CLASS + ")");
        sql("DELETE FROM courses WHERE id IN (" + COURSE + "," + OTHER_COURSE + ")");
        sql("DELETE FROM subjects WHERE id = " + SUBJECT);
        sql("DELETE FROM institutions WHERE id IN (" + INSTITUTION + "," + OTHER_INSTITUTION + ")");
        sql("DELETE FROM school_years WHERE id = " + YEAR);

        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (" + INSTITUTION
                + ", 'I122A', 'Analytics #122 A', now(), now()), (" + OTHER_INSTITUTION
                + ", 'I122B', 'Analytics #122 B', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES (" + YEAR
                + ", 2124, 2125, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES (" + COURSE
                + ", 'C122A', 'Curso analytics A', " + INSTITUTION + ", now(), now()), (" + OTHER_COURSE
                + ", 'C122B', 'Curso analytics B', " + OTHER_INSTITUTION + ", now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES ("
                + CLASS + ", '12A', 12, " + COURSE + ", " + YEAR + ", " + INSTITUTION + ", now(), now()), ("
                + OTHER_CLASS + ", '12B', 12, " + OTHER_COURSE + ", " + YEAR + ", " + OTHER_INSTITUTION
                + ", now(), now())");
        sql("INSERT INTO subjects (id, code, name, created_at, updated_at) VALUES (" + SUBJECT
                + ", 'A122', 'Analytics', now(), now())");
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, active) VALUES "
                + "(" + TEACHER + ", 'teacher-analytics-122', 'teacher-analytics-122@kixi.test', 'x', true, true),"
                + "(" + STUDENT + ", 'student-analytics-122', 'student-analytics-122@kixi.test', 'x', true, true),"
                + "(" + OTHER_STUDENT + ", 'student-other-analytics-122', 'student-other-analytics-122@kixi.test', 'x', true, true)");
        role(TEACHER, "TEACHER");
        role(STUDENT, "STUDENT");
        role(OTHER_STUDENT, "STUDENT");
        sql("INSERT INTO users (account_id, first_name, last_name, created_at, updated_at) VALUES "
                + "(" + STUDENT + ", 'Student', 'Analytics', now(), now()), (" + OTHER_STUDENT
                + ", 'Other', 'School', now(), now())");
        sql("INSERT INTO teachers (id, account_id, first_name, last_name, created_at, updated_at) VALUES ("
                + TEACHER_ROW + ", " + TEACHER + ", 'Teacher', 'Analytics', now(), now())");
        sql("INSERT INTO teaching_assignments (teacher_id, class_id, subject_id, school_year_id, created_at) VALUES ("
                + TEACHER_ROW + ", " + CLASS + ", " + SUBJECT + ", " + YEAR + ", now())");
        sql("INSERT INTO enrollments (account_id, class_id, school_year_id, status, created_at, updated_at) VALUES ("
                + STUDENT + ", " + CLASS + ", " + YEAR + ", 'ACTIVE', now(), now()), (" + OTHER_STUDENT
                + ", " + OTHER_CLASS + ", " + YEAR + ", 'ACTIVE', now(), now())");
        sql("INSERT INTO statements (id, exam_type, title, total_max_score, subject_id, class_id, institution_id,"
                + " school_year_id, visible, needs_review, source, created_at, updated_at) VALUES ("
                + STATEMENT + ", 'TEST', 'Correcao #122', 5, " + SUBJECT + ", " + CLASS + ", " + INSTITUTION
                + ", " + YEAR + ", true, false, 'MANUAL', now(), now())");
        sql("INSERT INTO questions (id, statement_id, number, text, question_type, max_score, page_index,"
                + " needs_review, created_at, updated_at) VALUES (" + QUESTION + ", " + STATEMENT
                + ", 1, 'Explique a resposta', 'OPEN', 5, 0, false, now(), now())");
        teacherToken = jwtService.generateToken(TEACHER, List.of("TEACHER"));
        studentToken = jwtService.generateToken(STUDENT, List.of("STUDENT"));
        otherStudentToken = jwtService.generateToken(OTHER_STUDENT, List.of("STUDENT"));
    }

    @Test
    void correctedSubmissionFlowsIntoAnalyticsGamificationAndLeaderboard() {
        AtomicLong simulationId = new AtomicLong();
        bearer(studentToken).post().uri("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + STUDENT + ",\"statementId\":" + STATEMENT
                        + ",\"schoolYearId\":" + YEAR + "}")
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.id")
                .value(value -> simulationId.set(((Number) value).longValue()));

        AtomicLong answerId = new AtomicLong();
        bearer(studentToken).post().uri("/api/v1/simulation-answers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"simulationId\":" + simulationId + ",\"questionId\":" + QUESTION
                        + ",\"answerText\":\"A resposta do aluno\"}")
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.id")
                .value(value -> answerId.set(((Number) value).longValue()));

        bearer(studentToken).post().uri("/api/v1/simulations/" + simulationId + "/submit")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.status").isEqualTo("FINISHED")
                .jsonPath("$.finalScore").isEqualTo(0.0);

        bearer(studentToken).get().uri("/api/v1/me/stats").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.xp").isEqualTo(1);

        bearer(teacherToken).patch().uri("/api/v1/simulation-answers/" + answerId + "/grade")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"scoreObtained\":4}")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.reviewStatus").isEqualTo("GRADED")
                .jsonPath("$.scoreObtained").isEqualTo(4.0);

        bearer(studentToken).get().uri("/api/v1/me/stats").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.xp").isEqualTo(80);

        bearer(teacherToken).get().uri("/api/v1/analytics/overview").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.scope").isEqualTo("TEACHER")
                .jsonPath("$.simulations.total").isEqualTo(1)
                .jsonPath("$.simulations.finished").isEqualTo(1)
                .jsonPath("$.averageScorePercent").isEqualTo(80.0)
                .jsonPath("$.passRatePercent").isEqualTo(100.0);

        sql("INSERT INTO simulations (id, account_id, statement_id, school_year_id, status, final_score,"
                + " time_spent_seconds, started_at, finished_at, created_at, updated_at) VALUES ("
                + OTHER_STUDENT + ", " + OTHER_STUDENT + ", " + STATEMENT + ", " + YEAR
                + ", 'FINISHED', 5, 600, now(), now(), now(), now())");

        bearer(otherStudentToken).get().uri("/api/v1/leaderboard?scope=class&statementId=" + STATEMENT)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.groupId").isEqualTo((int) OTHER_CLASS)
                .jsonPath("$.totalStudents").isEqualTo(1)
                .jsonPath("$.myAverage").isEqualTo(100.0);

        bearer(studentToken).get().uri("/api/v1/leaderboard?scope=class&statementId=" + STATEMENT)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.scope").isEqualTo("class")
                .jsonPath("$.groupId").isEqualTo((int) CLASS)
                .jsonPath("$.totalStudents").isEqualTo(1)
                .jsonPath("$.rankedStudents").isEqualTo(1)
                .jsonPath("$.myPosition").isEqualTo(1)
                .jsonPath("$.myAverage").isEqualTo(80.0)
                .jsonPath("$.entries[0].average").isEqualTo(80.0);

        bearer(studentToken).get().uri("/api/v1/leaderboard?scope=global&statementId=" + STATEMENT)
                .exchange().expectStatus().isForbidden();

        bearer(studentToken).put().uri("/api/v1/leaderboard/consent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"crossInstitution\":true}")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.crossInstitution").isEqualTo(true);

        bearer(studentToken).get().uri("/api/v1/leaderboard?scope=global&statementId=" + STATEMENT)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.scope").isEqualTo("global");

        bearer(studentToken).put().uri("/api/v1/leaderboard/consent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"crossInstitution\":false}")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.crossInstitution").isEqualTo(false);

        bearer(studentToken).get().uri("/api/v1/leaderboard?scope=global&statementId=" + STATEMENT)
                .exchange().expectStatus().isForbidden();
    }

    private void role(long accountId, String role) {
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + accountId
                + ", id FROM roles WHERE name = '" + role + "'");
    }

    private WebTestClient bearer(String token) {
        return client.mutate().defaultHeaders(headers -> headers.setBearerAuth(token)).build();
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(10));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
