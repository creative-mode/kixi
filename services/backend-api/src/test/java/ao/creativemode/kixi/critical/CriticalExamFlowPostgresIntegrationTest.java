package ao.creativemode.kixi.critical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import ao.creativemode.kixi.identity.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The critical public flow against PostgreSQL migrated by Flyway.
 *
 * <p>The fixture supplies persisted accounts and roles. Publication, answer-key
 * handling, simulation, correction and the result contract all go through the
 * real HTTP application context.</p>
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
class CriticalExamFlowPostgresIntegrationTest {

    private static final long TEACHER = 122001L;
    private static final long STUDENT = 122002L;
    /** Professor real sem acesso à sala do teste: tem de existir na base para o filtro de sessão o autenticar (kixi#450). */
    private static final long OTHER_TEACHER = 122003L;
    private static final long STATEMENT = 122001L;
    private static final long QUESTION = 122001L;
    private static final long WRONG_OPTION = 122001L;
    private static final long RIGHT_OPTION = 122002L;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private JwtService jwtService;

    private WebTestClient client;
    private String teacherToken;
    private String studentToken;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException(
                    "CriticalExamFlowPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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
        client = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .responseTimeout(Duration.ofSeconds(20))
                .build();

        sql("DELETE FROM simulation_answers WHERE simulation_id IN "
                + "(SELECT id FROM simulations WHERE statement_id = " + STATEMENT + ")");
        sql("DELETE FROM exam_room_participants WHERE exam_room_id IN "
                + "(SELECT id FROM exam_rooms WHERE statement_id = " + STATEMENT + ")");
        sql("DELETE FROM simulations WHERE statement_id = " + STATEMENT);
        sql("DELETE FROM exam_rooms WHERE statement_id = " + STATEMENT);
        sql("DELETE FROM question_options WHERE question_id = " + QUESTION);
        sql("DELETE FROM questions WHERE id = " + QUESTION);
        sql("DELETE FROM statements WHERE id = " + STATEMENT);
        sql("DELETE FROM account_roles WHERE account_id IN (" + TEACHER + ", " + STUDENT + ", " + OTHER_TEACHER + ")");
        sql("DELETE FROM accounts WHERE id IN (" + TEACHER + ", " + STUDENT + ", " + OTHER_TEACHER + ")");

        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) VALUES "
                + "(" + TEACHER + ", 'it-teacher-122', 'it-teacher-122@example.com', 'x', true),"
                + "(" + STUDENT + ", 'it-student-122', 'it-student-122@example.com', 'x', true),"
                // Um professor real sem acesso àquela sala: desde kixi#450 um token de uma conta que não existe já nem autentica (401).
                + "(" + OTHER_TEACHER + ", 'it-teacher-other-122', 'it-teacher-other-122@example.com', 'x', true)");
        sql("INSERT INTO account_roles (account_id, role_id) "
                + "SELECT " + OTHER_TEACHER + ", id FROM roles WHERE name = 'TEACHER'");
        sql("INSERT INTO account_roles (account_id, role_id) "
                + "SELECT " + TEACHER + ", id FROM roles WHERE name = 'TEACHER'");
        sql("INSERT INTO account_roles (account_id, role_id) "
                + "SELECT " + STUDENT + ", id FROM roles WHERE name = 'STUDENT'");
        sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, "
                + "created_by, visible, needs_review, source) VALUES (" + STATEMENT
                + ", 'Prova de integracao #122', 'TEST', 60, 5, " + TEACHER + ", false, true, 'manual')");
        sql("INSERT INTO questions (id, statement_id, number, text, question_type, max_score, order_index) VALUES "
                + "(" + QUESTION + ", " + STATEMENT + ", 1, 'Quanto e 2 + 2?', 'multiple_choice', 5, 1)");
        sql("INSERT INTO question_options (id, question_id, option_label, option_text, is_correct, order_index) VALUES "
                + "(" + WRONG_OPTION + ", " + QUESTION + ", 'A', '3', false, 1),"
                + "(" + RIGHT_OPTION + ", " + QUESTION + ", 'B', '4', false, 2)");

        teacherToken = jwtService.generateToken(TEACHER, List.of("TEACHER"));
        studentToken = jwtService.generateToken(STUDENT, List.of("STUDENT"));
    }

    @Test
    void teacherPublishesKeyStudentSimulatesAndReceivesCorrectScore() {
        bearer(teacherToken)
                .put()
                .uri("/api/v1/statements/" + STATEMENT + "/questions/" + QUESTION + "/correct-option")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"optionId\":" + RIGHT_OPTION + "}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(RIGHT_OPTION)
                .jsonPath("$.isCorrect").isEqualTo(true);

        bearer(teacherToken)
                .post()
                .uri("/api/v1/statements/" + STATEMENT + "/approve")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.needsReview").isEqualTo(false);

        bearer(teacherToken)
                .patch()
                .uri("/api/v1/statements/" + STATEMENT + "/visibility?visible=true")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.visible").isEqualTo(true);

        AtomicLong simulationId = new AtomicLong();
        bearer(studentToken)
                .post()
                .uri("/api/v1/simulations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + STUDENT + ",\"statementId\":" + STATEMENT + "}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").value(value -> simulationId.set(((Number) value).longValue()));
        long simulation = simulationId.get();

        bearer(studentToken)
                .post()
                .uri("/api/v1/simulation-answers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"simulationId\":" + simulation + ",\"questionId\":" + QUESTION
                        + ",\"selectedOptionId\":" + RIGHT_OPTION + "}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.selectedOptionId").isEqualTo(RIGHT_OPTION);

        bearer(studentToken)
                .post()
                .uri("/api/v1/simulations/" + simulation + "/submit")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("FINISHED")
                .jsonPath("$.finalScore").isEqualTo(5.0)
                .jsonPath("$.correctAnswers").isEqualTo(1)
                .jsonPath("$.questions[0].selectedOptionId").isEqualTo(RIGHT_OPTION)
                .jsonPath("$.questions[0].correctOptionId").isEqualTo(RIGHT_OPTION)
                .jsonPath("$.questions[0].scoreObtained").isEqualTo(5.0)
                .jsonPath("$.questions[0].isCorrect").isEqualTo(true);
    }

    @Test
    void studentEventsArePersistedAndVisibleOnlyToAuthorizedRoomStaff() {
        sql("UPDATE statements SET visible = true, needs_review = false WHERE id = " + STATEMENT);
        AtomicLong simulationId = new AtomicLong();
        bearer(studentToken)
                .post()
                .uri("/api/v1/simulations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + STUDENT + ",\"statementId\":" + STATEMENT + "}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").value(value -> simulationId.set(((Number) value).longValue()));
        long simulation = simulationId.get();

        long room = database.sql("INSERT INTO exam_rooms (statement_id, teacher_account_id, starts_at, ends_at, "
                        + "duration_minutes, status) VALUES (:statement, :teacher, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP + INTERVAL '1 hour', 60, 'RUNNING') RETURNING id")
                .bind("statement", STATEMENT).bind("teacher", TEACHER)
                .map((row, metadata) -> row.get("id", Long.class))
                .one().block(Duration.ofSeconds(10));
        sql("UPDATE simulations SET exam_room_id = " + room + ", exam_room_duration_minutes = 60 WHERE id = "
                + simulation);
        sql("INSERT INTO exam_room_participants (exam_room_id, account_id, simulation_id) VALUES (" + room + ", "
                + STUDENT + ", " + simulation + ")");

        bearer(studentToken)
                .post()
                .uri("/api/v1/simulations/" + simulation + "/integrity-events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"kind\":\"TAB_HIDDEN\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.simulationId").isEqualTo(simulation)
                .jsonPath("$.kind").isEqualTo("TAB_HIDDEN")
                .jsonPath("$.at").exists()
                .jsonPath("$.questionId").doesNotExist();

        bearer(studentToken)
                .post()
                .uri("/api/v1/simulations/" + simulation + "/integrity-events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"kind\":\"PASTE_BLOCKED\",\"questionId\":" + QUESTION + "}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.kind").isEqualTo("PASTE_BLOCKED")
                .jsonPath("$.questionId").isEqualTo(QUESTION);

        bearer(teacherToken)
                .get()
                .uri("/api/v1/exam-rooms/" + room + "/integrity")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.roomId").isEqualTo(room)
                .jsonPath("$.participants[0].accountId").isEqualTo(STUDENT)
                .jsonPath("$.participants[0].simulationId").isEqualTo(simulation)
                .jsonPath("$.participants[0].events.length()").isEqualTo(2)
                .jsonPath("$.participants[0].events[1].questionId").isEqualTo(QUESTION);

        String otherTeacherToken = jwtService.generateToken(OTHER_TEACHER, List.of("TEACHER"));
        bearer(otherTeacherToken).get().uri("/api/v1/exam-rooms/" + room + "/integrity")
                .exchange().expectStatus().isForbidden();
        bearer(studentToken).get().uri("/api/v1/exam-rooms/" + room + "/integrity")
                .exchange().expectStatus().isForbidden();
        client.get().uri("/api/v1/exam-rooms/" + room + "/integrity")
                .exchange().expectStatus().isUnauthorized();

        Long saved = database.sql("SELECT COUNT(*) AS n FROM simulation_integrity_events WHERE simulation_id = :id")
                .bind("id", simulation)
                .map((row, metadata) -> row.get("n", Long.class))
                .one().block(Duration.ofSeconds(10));
        assertThat(saved).isEqualTo(2L);
    }

    @Test
    void listFiltersAndPagesInSqlForTheStatementsTeacher() {
        sql("UPDATE statements SET visible = true, needs_review = false WHERE id = " + STATEMENT);
        bearer(studentToken)
                .post()
                .uri("/api/v1/simulations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + STUDENT + ",\"statementId\":" + STATEMENT + "}")
                .exchange()
                .expectStatus().isCreated();

        bearer(teacherToken)
                .get()
                .uri("/api/v1/simulations?statementId=" + STATEMENT + "&status=IN_PROGRESS&page=0&size=1")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("X-Total-Count", "[1-9][0-9]*")
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].statement.id").isEqualTo((int) STATEMENT);

        bearer(teacherToken)
                .get()
                .uri("/api/v1/simulations?statementId=999999999")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Total-Count", "0")
                .expectBody()
                .jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void rejectsEventsAfterTheDeadlineEvenWhileTheSweepIsDisabled() {
        sql("UPDATE statements SET visible = true, needs_review = false WHERE id = " + STATEMENT);
        AtomicLong simulationId = new AtomicLong();
        bearer(studentToken)
                .post()
                .uri("/api/v1/simulations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + STUDENT + ",\"statementId\":" + STATEMENT + "}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").value(value -> simulationId.set(((Number) value).longValue()));
        long simulation = simulationId.get();
        // The sweep is disabled in this context, so the status stays IN_PROGRESS:
        // only the deadline check can refuse the event.
        sql("UPDATE simulations SET started_at = CURRENT_TIMESTAMP - INTERVAL '2 days' WHERE id = " + simulation);

        bearer(studentToken)
                .post()
                .uri("/api/v1/simulations/" + simulation + "/integrity-events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"kind\":\"TAB_HIDDEN\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);

        Long saved = database.sql("SELECT COUNT(*) AS n FROM simulation_integrity_events WHERE simulation_id = :id")
                .bind("id", simulation)
                .map((row, metadata) -> row.get("n", Long.class))
                .one().block(Duration.ofSeconds(10));
        assertThat(saved).isZero();
    }

    @Test
    void concurrentEventWritesCannotExceedPerSimulationLimit() throws Exception {
        sql("UPDATE statements SET visible = true, needs_review = false WHERE id = " + STATEMENT);
        AtomicLong simulationId = new AtomicLong();
        bearer(studentToken)
                .post()
                .uri("/api/v1/simulations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + STUDENT + ",\"statementId\":" + STATEMENT + "}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").value(value -> simulationId.set(((Number) value).longValue()));
        long simulation = simulationId.get();

        database.sql("INSERT INTO simulation_integrity_events (simulation_id, kind, occurred_at) "
                        + "SELECT :id, 'TAB_HIDDEN', CURRENT_TIMESTAMP FROM generate_series(1, 119)")
                .bind("id", simulation)
                .fetch().rowsUpdated().block(Duration.ofSeconds(10));

        var first = CompletableFuture.supplyAsync(() -> postIntegrityEvent(simulation));
        var second = CompletableFuture.supplyAsync(() -> postIntegrityEvent(simulation));
        List<Integer> statuses = List.of(
                first.get(20, TimeUnit.SECONDS).value(),
                second.get(20, TimeUnit.SECONDS).value());

        assertThat(statuses).containsExactlyInAnyOrder(
                HttpStatus.CREATED.value(), HttpStatus.TOO_MANY_REQUESTS.value());
        Long persisted = database.sql("SELECT COUNT(*) AS n FROM simulation_integrity_events WHERE simulation_id = :id")
                .bind("id", simulation)
                .map((row, metadata) -> row.get("n", Long.class))
                .one().block(Duration.ofSeconds(10));
        assertThat(persisted).isEqualTo(120L);
    }

    private HttpStatusCode postIntegrityEvent(long simulationId) {
        return bearer(studentToken)
                .post()
                .uri("/api/v1/simulations/" + simulationId + "/integrity-events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"kind\":\"TAB_HIDDEN\"}")
                .exchange()
                .returnResult(String.class)
                .getStatus();
    }

    @Test
    void studentCannotSetKeyApproveOrPublish() {
        bearer(studentToken)
                .put()
                .uri("/api/v1/statements/" + STATEMENT + "/questions/" + QUESTION + "/correct-option")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"optionId\":" + RIGHT_OPTION + "}")
                .exchange()
                .expectStatus().isForbidden();

        bearer(studentToken)
                .post()
                .uri("/api/v1/statements/" + STATEMENT + "/approve")
                .exchange()
                .expectStatus().isForbidden();

        bearer(studentToken)
                .patch()
                .uri("/api/v1/statements/" + STATEMENT + "/visibility?visible=true")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void anonymousCannotSetKeyApproveOrPublish() {
        client.put()
                .uri("/api/v1/statements/" + STATEMENT + "/questions/" + QUESTION + "/correct-option")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"optionId\":" + RIGHT_OPTION + "}")
                .exchange()
                .expectStatus().isUnauthorized();

        client.post()
                .uri("/api/v1/statements/" + STATEMENT + "/approve")
                .exchange()
                .expectStatus().isUnauthorized();

        client.patch()
                .uri("/api/v1/statements/" + STATEMENT + "/visibility?visible=true")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(10));
    }

    private WebTestClient bearer(String token) {
        return client.mutate().defaultHeaders(headers -> headers.setBearerAuth(token)).build();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
