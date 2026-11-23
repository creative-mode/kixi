package ao.creativemode.kixi.examrooms.controller;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import ao.creativemode.kixi.identity.service.JwtService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * {@code GET /exam-rooms/owned} (kixi#600): the SchoolOps engine runs as a dedicated INTEGRATION account that owns
 * no room, and it finds a statement's room there to tell a digital exam from a paper one. Teachers and ADMIN keep
 * seeing only their own rooms.
 */
@SpringBootTest(properties = {
        "spring.r2dbc.pool.max-size=2",
        "spring.r2dbc.pool.initial-size=0",
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
class ExamRoomOwnedPostgresIntegrationTest {

    private static final long TEACHER_A = 600001L;
    private static final long TEACHER_B = 600002L;
    private static final long ENGINE = 600003L;
    private static final long ADMIN = 600004L;
    private static final long STATEMENT = 600001L;
    private static final long ROOM_A = 600001L;
    private static final long ROOM_B = 600002L;
    /** Só usados por {@link #engineFiltersByInstitutionIdThroughTheJoinToClasses}. */
    private static final long INSTITUTION = 600001L;
    private static final long SCHOOL_YEAR = 600001L;
    private static final long COURSE = 600001L;
    private static final long CLASS = 600001L;

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private DatabaseClient database;
    @Autowired
    private JwtService jwtService;

    private WebTestClient client;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("ExamRoomOwnedPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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
        client = WebTestClient.bindToApplicationContext(applicationContext).apply(springSecurity())
                .configureClient().responseTimeout(Duration.ofSeconds(20)).build();
        sql("DELETE FROM exam_rooms WHERE id IN (" + ROOM_A + ", " + ROOM_B + ")");
        sql("DELETE FROM statements WHERE id = " + STATEMENT);
        sql("DELETE FROM account_roles WHERE account_id IN (" + TEACHER_A + ", " + TEACHER_B + ", " + ENGINE + ", " + ADMIN + ")");
        sql("DELETE FROM accounts WHERE id IN (" + TEACHER_A + ", " + TEACHER_B + ", " + ENGINE + ", " + ADMIN + ")");
        // kixi#600: a turma e a escola que engineFiltersByInstitutionIdThroughTheJoinToClasses cria; limpas aqui também,
        // para uma corrida anterior que tenha falhado a meio não deixar lixo para a próxima.
        sql("DELETE FROM classes WHERE id = " + CLASS);
        sql("DELETE FROM courses WHERE id = " + COURSE);
        sql("DELETE FROM school_years WHERE id = " + SCHOOL_YEAR);
        sql("DELETE FROM institutions WHERE id = " + INSTITUTION);
        for (long id : new long[] {TEACHER_A, TEACHER_B, ENGINE, ADMIN}) {
            sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) VALUES (" + id + ", 'it-owned-" + id
                    + "', 'it-owned-" + id + "@example.com', 'x', true)");
        }
        role(TEACHER_A, "TEACHER");
        role(TEACHER_B, "TEACHER");
        role(ENGINE, "INTEGRATION");
        role(ADMIN, "ADMIN");
        sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, created_by, visible, needs_review, source) "
                + "VALUES (" + STATEMENT + ", 'Prova #600', 'TEST', 60, 5, " + TEACHER_A + ", false, true, 'manual')");
        for (long[] room : new long[][] {{ROOM_A, TEACHER_A}, {ROOM_B, TEACHER_B}}) {
            sql("INSERT INTO exam_rooms (id, statement_id, teacher_account_id, starts_at, ends_at, duration_minutes, status) VALUES ("
                    + room[0] + ", " + STATEMENT + ", " + room[1] + ", now() - interval '1 hour', now() + interval '1 hour', 60, 'RUNNING')");
        }
    }

    @Test
    void engineSeesTheRoomsOfEveryTeacher() {
        client.get().uri("/api/v1/exam-rooms/owned").header("Authorization", "Bearer " + token(ENGINE, "INTEGRATION")).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$[?(@.id==" + ROOM_A + ")]").exists()
                .jsonPath("$[?(@.id==" + ROOM_B + ")]").exists();
    }

    @Test
    void teacherAndAdminKeepSeeingOnlyTheirOwn() {
        client.get().uri("/api/v1/exam-rooms/owned").header("Authorization", "Bearer " + token(TEACHER_A, "TEACHER")).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$[?(@.id==" + ROOM_A + ")]").exists()
                .jsonPath("$[?(@.id==" + ROOM_B + ")]").doesNotExist();
        client.get().uri("/api/v1/exam-rooms/owned").header("Authorization", "Bearer " + token(ADMIN, "ADMIN")).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$[?(@.id==" + ROOM_A + ")]").doesNotExist()
                .jsonPath("$[?(@.id==" + ROOM_B + ")]").doesNotExist();
    }

    /** kixi#600: com `institutionId`, o INTEGRATION vê só as salas cuja turma é da escola, pelo novo JOIN a classes. */
    @Test
    void engineFiltersByInstitutionIdThroughTheJoinToClasses() {
        sql("INSERT INTO institutions (id, code, name) VALUES (" + INSTITUTION + ", 'IT600', 'Escola #600')");
        // school_years não tem institution_id (é global ao sistema); start_year/end_year têm de ser únicos.
        sql("INSERT INTO school_years (id, start_year, end_year) VALUES (" + SCHOOL_YEAR + ", 2600, 2601)");
        sql("INSERT INTO courses (id, code, name, institution_id) VALUES (" + COURSE + ", 'IT600-C', 'Curso #600', " + INSTITUTION + ")");
        sql("INSERT INTO classes (id, grade, course_id, school_year_id, institution_id) VALUES (" + CLASS + ", 10, " + COURSE + ", "
                + SCHOOL_YEAR + ", " + INSTITUTION + ")");
        // Só a ROOM_A fica ligada à turma da escola #600; a ROOM_B fica sem turma, como na fixture base.
        sql("UPDATE exam_rooms SET class_id = " + CLASS + " WHERE id = " + ROOM_A);

        client.get().uri("/api/v1/exam-rooms/owned?institutionId=" + INSTITUTION)
                .header("Authorization", "Bearer " + token(ENGINE, "INTEGRATION")).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$[?(@.id==" + ROOM_A + ")]").exists()
                .jsonPath("$[?(@.id==" + ROOM_B + ")]").doesNotExist();
    }

    private String token(long account, String role) {
        return jwtService.generateToken(account, List.of(role));
    }

    private void role(long account, String role) {
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + account + ", id FROM roles WHERE name = '" + role + "'");
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(20));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
