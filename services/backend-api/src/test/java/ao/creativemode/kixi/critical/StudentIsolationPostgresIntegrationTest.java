package ao.creativemode.kixi.critical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import ao.creativemode.kixi.identity.service.JwtService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Um aluno não chega ao que é de outro (kixi#453), contra PostgreSQL migrado pelo Flyway. O {@code SecurityConfig}
 * deixa qualquer autenticado fazer GET em simulações, respostas e enunciados: o que protege é o serviço, e este
 * teste percorre cada rota com o recurso do aluno B nas mãos do aluno A. Uma rota nova que herde "autenticado"
 * sem guarda no serviço aparece aqui se for acrescentada à lista.
 *
 * <p>Também prova, em Postgres real, o que um mock não prova: duas submissões simultâneas fecham a simulação uma
 * só vez, e a mesma resposta enviada duas vezes fica numa só linha.</p>
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
class StudentIsolationPostgresIntegrationTest {

    private static final long TEACHER = 123001L;
    private static final long STUDENT_A = 123002L;
    private static final long STUDENT_B = 123003L;
    private static final long STATEMENT = 123001L;
    private static final long QUESTION = 123001L;
    private static final long WRONG_OPTION = 123001L;
    private static final long RIGHT_OPTION = 123002L;

    /** Preenchidos no fixture: o id da simulação e da resposta do aluno B. */
    private static final AtomicLong B_SIMULATION = new AtomicLong();
    private static final AtomicLong B_ANSWER = new AtomicLong();

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private DatabaseClient database;
    @Autowired
    private JwtService jwtService;

    private WebTestClient client;
    private String studentA;
    private String studentB;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException(
                    "StudentIsolationPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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
        sql("DELETE FROM account_roles WHERE account_id IN (" + TEACHER + ", " + STUDENT_A + ", " + STUDENT_B + ")");
        sql("DELETE FROM accounts WHERE id IN (" + TEACHER + ", " + STUDENT_A + ", " + STUDENT_B + ")");
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) VALUES "
                + "(" + TEACHER + ", 'it-iso-teacher', 'it-iso-teacher@example.com', 'x', true),"
                + "(" + STUDENT_A + ", 'it-iso-a', 'it-iso-a@example.com', 'x', true),"
                + "(" + STUDENT_B + ", 'it-iso-b', 'it-iso-b@example.com', 'x', true)");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + TEACHER + ", id FROM roles WHERE name = 'TEACHER'");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + STUDENT_A + ", id FROM roles WHERE name = 'STUDENT'");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + STUDENT_B + ", id FROM roles WHERE name = 'STUDENT'");
        sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, "
                + "created_by, visible, needs_review, source) VALUES (" + STATEMENT
                + ", 'Prova de isolamento #453', 'TEST', 60, 5, " + TEACHER + ", true, false, 'manual')");
        sql("INSERT INTO questions (id, statement_id, number, text, question_type, max_score, order_index) VALUES "
                + "(" + QUESTION + ", " + STATEMENT + ", 1, 'Quanto e 2 + 2?', 'multiple_choice', 5, 1)");
        sql("INSERT INTO question_options (id, question_id, option_label, option_text, is_correct, order_index) VALUES "
                + "(" + WRONG_OPTION + ", " + QUESTION + ", 'A', '3', false, 1),"
                + "(" + RIGHT_OPTION + ", " + QUESTION + ", 'B', '4', true, 2)");

        studentA = jwtService.generateToken(STUDENT_A, List.of("STUDENT"));
        studentB = jwtService.generateToken(STUDENT_B, List.of("STUDENT"));

        B_SIMULATION.set(startSimulation(studentB, STUDENT_B));
        B_ANSWER.set(saveAnswer(studentB, B_SIMULATION.get()));
    }

    /** Método, caminho (com {sim} e {ans}) e corpo de cada rota que o aluno A tenta no que é do B. */
    static Stream<Arguments> routesOfStudentB() {
        String answerBody = "{\"simulationId\":{sim},\"questionId\":" + QUESTION + ",\"selectedOptionId\":" + RIGHT_OPTION + "}";
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/api/v1/simulations/{sim}", null),
                Arguments.of(HttpMethod.GET, "/api/v1/simulations/{sim}/result", null),
                Arguments.of(HttpMethod.PUT, "/api/v1/simulations/{sim}",
                        "{\"accountId\":" + STUDENT_B + ",\"statementId\":" + STATEMENT + "}"),
                Arguments.of(HttpMethod.POST, "/api/v1/simulations/{sim}/submit", null),
                Arguments.of(HttpMethod.POST, "/api/v1/simulations/{sim}/integrity-events", "{\"kind\":\"TAB_HIDDEN\"}"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/simulations/{sim}", null),
                Arguments.of(HttpMethod.DELETE, "/api/v1/simulations/{sim}/permanent", null),
                Arguments.of(HttpMethod.GET, "/api/v1/simulation-answers/{ans}", null),
                Arguments.of(HttpMethod.PUT, "/api/v1/simulation-answers/{ans}", answerBody),
                Arguments.of(HttpMethod.POST, "/api/v1/simulation-answers", answerBody),
                Arguments.of(HttpMethod.PATCH, "/api/v1/simulation-answers/{ans}/grade", "{\"scoreObtained\":5}"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/simulation-answers/{ans}", null));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("routesOfStudentB")
    void studentACannotReadOrChangeWhatBelongsToStudentB(HttpMethod method, String template, String body) {
        String path = template.replace("{sim}", String.valueOf(B_SIMULATION.get()))
                .replace("{ans}", String.valueOf(B_ANSWER.get()));
        WebTestClient.RequestBodySpec request = bearer(studentA).method(method).uri(path)
                .contentType(MediaType.APPLICATION_JSON);

        var result = (body == null ? request.exchange()
                : request.bodyValue(body.replace("{sim}", String.valueOf(B_SIMULATION.get()))).exchange())
                .returnResult(String.class);
        HttpStatusCode status = result.getStatus();

        assertThat(status.value()).as("%s %s", method, path).isIn(401, 403, 404);
        // Nada mudou do lado do B, seja qual for a resposta.
        assertThat(count("SELECT COUNT(*) AS n FROM simulations WHERE id = " + B_SIMULATION.get()
                + " AND status = 'IN_PROGRESS' AND deleted_at IS NULL")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) AS n FROM simulation_answers WHERE id = " + B_ANSWER.get()
                + " AND deleted_at IS NULL")).isEqualTo(1L);
    }

    @Test
    void theListOfStudentAHasNothingOfStudentB() {
        bearer(studentA).get().uri("/api/v1/simulations").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(0);
        bearer(studentA).get().uri("/api/v1/simulation-answers").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void twoSimultaneousSubmissionsCloseTheSimulationOnce() {
        CompletableFuture<HttpStatusCode> first = CompletableFuture.supplyAsync(() -> submit(studentB));
        CompletableFuture<HttpStatusCode> second = CompletableFuture.supplyAsync(() -> submit(studentB));

        List<Integer> statuses = List.of(first.join().value(), second.join().value());

        assertThat(statuses).as("at least one submission wins").contains(200);
        assertThat(statuses).as("none of them is a server error").allMatch(code -> code < 500);
        assertThat(count("SELECT COUNT(*) AS n FROM simulations WHERE id = " + B_SIMULATION.get()
                + " AND status = 'FINISHED'")).isEqualTo(1L);
    }

    @Test
    void theSameAnswerSentTwiceStaysInOneRow() {
        // A segunda gravação é um 409 (ou um 2xx idempotente): o que não pode é criar uma segunda linha.
        bearer(studentB).post().uri("/api/v1/simulation-answers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"simulationId\":" + B_SIMULATION.get() + ",\"questionId\":" + QUESTION
                        + ",\"selectedOptionId\":" + RIGHT_OPTION + "}")
                .exchange().expectStatus().value(code -> assertThat(code).isIn(200, 201, 409));

        assertThat(count("SELECT COUNT(*) AS n FROM simulation_answers WHERE simulation_id = "
                + B_SIMULATION.get() + " AND question_id = " + QUESTION + " AND deleted_at IS NULL")).isEqualTo(1L);
    }

    private HttpStatusCode submit(String token) {
        return bearer(token).post().uri("/api/v1/simulations/" + B_SIMULATION.get() + "/submit")
                .exchange().returnResult(String.class).getStatus();
    }

    private long startSimulation(String token, long account) {
        AtomicLong id = new AtomicLong();
        bearer(token).post().uri("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + account + ",\"statementId\":" + STATEMENT + "}")
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.id").value(value -> id.set(((Number) value).longValue()));
        return id.get();
    }

    private long saveAnswer(String token, long simulation) {
        AtomicLong id = new AtomicLong();
        bearer(token).post().uri("/api/v1/simulation-answers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"simulationId\":" + simulation + ",\"questionId\":" + QUESTION
                        + ",\"selectedOptionId\":" + RIGHT_OPTION + "}")
                .exchange().expectStatus().is2xxSuccessful()
                .expectBody().jsonPath("$.id").value(value -> id.set(((Number) value).longValue()));
        return id.get();
    }

    private Long count(String query) {
        return database.sql(query).map((row, metadata) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(20));
    }

    private WebTestClient bearer(String token) {
        return client.mutate().defaultHeaders(headers -> headers.setBearerAuth(token)).build();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
