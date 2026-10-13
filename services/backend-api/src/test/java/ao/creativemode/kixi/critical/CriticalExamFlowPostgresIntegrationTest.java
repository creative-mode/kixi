package ao.creativemode.kixi.critical;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import ao.creativemode.kixi.identity.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.http.MediaType;
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
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
class CriticalExamFlowPostgresIntegrationTest {

    private static final long TEACHER = 122001L;
    private static final long STUDENT = 122002L;
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
        sql("DELETE FROM simulations WHERE statement_id = " + STATEMENT);
        sql("DELETE FROM question_options WHERE question_id = " + QUESTION);
        sql("DELETE FROM questions WHERE id = " + QUESTION);
        sql("DELETE FROM statements WHERE id = " + STATEMENT);
        sql("DELETE FROM account_roles WHERE account_id IN (" + TEACHER + ", " + STUDENT + ")");
        sql("DELETE FROM accounts WHERE id IN (" + TEACHER + ", " + STUDENT + ")");

        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) VALUES "
                + "(" + TEACHER + ", 'it-teacher-122', 'it-teacher-122@example.com', 'x', true),"
                + "(" + STUDENT + ", 'it-student-122', 'it-student-122@example.com', 'x', true)");
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
