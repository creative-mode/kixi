package ao.creativemode.kixi.issue122;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import ao.creativemode.kixi.identity.service.JwtService;

/** Focused issue #122 flows not covered by the existing critical exam test. */
@SpringBootTest(properties = {
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
class EnrollmentExamRoomPostgresIntegrationTest {
    private static final long ADMIN = 122101L;
    private static final long STUDENT = 122102L;
    private static final long INSTITUTION = 122101L;
    private static final long YEAR = 122101L;
    private static final long COURSE = 122101L;
    private static final long CLASS = 122101L;
    private static final long STATEMENT = 122101L;

    @Autowired
    private ApplicationContext context;
    @Autowired
    private DatabaseClient database;
    @Autowired
    private JwtService jwtService;

    private WebTestClient client;
    private String adminToken;
    private String studentToken;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("EnrollmentExamRoomPostgresIntegrationTest requires PostgreSQL");
        }
    }

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
        sql("DELETE FROM simulation_answers WHERE simulation_id IN (SELECT id FROM simulations WHERE account_id IN (" + ADMIN + "," + STUDENT + "))");
        sql("UPDATE exam_room_participants SET simulation_id = NULL WHERE account_id IN (" + ADMIN + "," + STUDENT + ")");
        sql("DELETE FROM simulations WHERE account_id IN (" + ADMIN + "," + STUDENT + ")");
        sql("DELETE FROM exam_room_participants WHERE account_id IN (" + ADMIN + "," + STUDENT + ")");
        sql("DELETE FROM exam_rooms WHERE statement_id = " + STATEMENT);
        sql("DELETE FROM enrollments WHERE account_id = " + STUDENT);
        sql("DELETE FROM statements WHERE id = " + STATEMENT);
        sql("DELETE FROM account_roles WHERE account_id IN (" + ADMIN + "," + STUDENT + ")");
        sql("DELETE FROM accounts WHERE id IN (" + ADMIN + "," + STUDENT + ")");
        sql("DELETE FROM classes WHERE id = " + CLASS);
        sql("DELETE FROM courses WHERE id = " + COURSE);
        sql("DELETE FROM school_years WHERE id = " + YEAR);
        sql("DELETE FROM institutions WHERE id = " + INSTITUTION);

        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (" + INSTITUTION + ", 'I122', 'Issue 122', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES (" + YEAR + ", 2122, 2123, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES (" + COURSE + ", 'C122', 'Curso 122', " + INSTITUTION + ", now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES (" + CLASS + ", '12A', 12, " + COURSE + ", " + YEAR + ", " + INSTITUTION + ", now(), now())");
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, active) VALUES "
                + "(" + ADMIN + ", 'admin122', 'admin122@kixi.test', 'x', true, true),"
                + "(" + STUDENT + ", 'student122', 'student122@kixi.test', 'x', true, true)");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + ADMIN + ", id FROM roles WHERE name = 'ADMIN'");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + STUDENT + ", id FROM roles WHERE name = 'STUDENT'");
        sql("INSERT INTO enrollments (id, account_id, class_id, school_year_id) VALUES (" + STUDENT + ", " + STUDENT + ", " + CLASS + ", " + YEAR + ")");
        sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, created_by, class_id, school_year_id, visible, needs_review, source) VALUES ("
                + STATEMENT + ", 'Issue 122', 'TEST', 60, 1, " + ADMIN + ", " + CLASS + ", " + YEAR + ", false, false, 'manual')");
        adminToken = jwtService.generateToken(ADMIN, List.of("ADMIN"));
        studentToken = jwtService.generateToken(STUDENT, List.of("STUDENT"));
    }

    @Test
    void enrollmentUniquenessIsEnforcedByPostgresql() {
        assertThatThrownBy(() -> sql("INSERT INTO enrollments (account_id, class_id, school_year_id) VALUES ("
                + STUDENT + ", " + CLASS + ", " + YEAR + ")"))
                .hasMessageContaining("uq_enrollments_account_school_year_active");
    }

    @Test
    void adminCreatesRoomStudentJoinsAndGetsOneRoomSimulation() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        AtomicLong roomId = new AtomicLong();
        bearer(adminToken).post().uri("/api/v1/exam-rooms").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"statementId\":" + STATEMENT + ",\"classId\":" + CLASS
                        + ",\"startsAt\":\"" + now.minusMinutes(1) + "\",\"endsAt\":\"" + now.plusHours(1)
                        + "\",\"durationMinutes\":60}")
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.id")
                .value(value -> roomId.set(((Number) value).longValue()));

        bearer(adminToken).post().uri("/api/v1/exam-rooms/" + roomId + "/participants")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"accountId\":" + STUDENT + "}")
                .exchange().expectStatus().isOk();
        bearer(adminToken).post().uri("/api/v1/exam-rooms/" + roomId + "/open")
                .exchange().expectStatus().isOk();
        bearer(adminToken).post().uri("/api/v1/exam-rooms/" + roomId + "/start")
                .exchange().expectStatus().isOk();
        bearer(studentToken).post().uri("/api/v1/exam-rooms/" + roomId + "/join")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.simulationId").isNumber();

        Long count = database.sql("SELECT count(*) AS count FROM simulations WHERE exam_room_id = " + roomId)
                .map((row, metadata) -> row.get("count", Long.class)).one().block(Duration.ofSeconds(10));
        if (count == null || count != 1L) {
            throw new AssertionError("expected one exam-room simulation, got " + count);
        }
    }

    private WebTestClient bearer(String token) {
        return client.mutate().defaultHeaders(headers -> headers.setBearerAuth(token)).build();
    }
    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(10));
    }
    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
