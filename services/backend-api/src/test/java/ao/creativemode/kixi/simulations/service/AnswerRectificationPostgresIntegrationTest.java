package ao.creativemode.kixi.simulations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import ao.creativemode.kixi.identity.service.JwtService;
import java.time.Duration;
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

/**
 * Re-correção de uma resposta já corrigida (kixi#509): o motor SchoolOps rectifica a pontuação de uma dissertativa quando uma
 * reclamação é deferida. {@code PATCH .../grade?rectify=true} só aceita respostas já corrigidas, a corrigir pela primeira vez
 * continua a só aceitar as pendentes, e a nota final da simulação é recalculada.
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
class AnswerRectificationPostgresIntegrationTest {

    private static final long TEACHER = 508001L;
    private static final long STUDENT = 508002L;
    private static final long ENGINE = 508003L;
    private static final long STATEMENT = 508001L;
    private static final long QUESTION = 508001L;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private JwtService jwtService;

    private WebTestClient client;
    private String teacherToken;
    private String studentToken;
    private String engineToken;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("AnswerRectificationPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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
        sql("DELETE FROM simulation_answers WHERE simulation_id IN (SELECT id FROM simulations WHERE statement_id = " + STATEMENT + ")");
        sql("DELETE FROM simulations WHERE statement_id = " + STATEMENT);
        sql("DELETE FROM questions WHERE id = " + QUESTION);
        sql("DELETE FROM statements WHERE id = " + STATEMENT);
        sql("DELETE FROM account_roles WHERE account_id IN (" + TEACHER + ", " + STUDENT + ", " + ENGINE + ")");
        sql("DELETE FROM accounts WHERE id IN (" + TEACHER + ", " + STUDENT + ", " + ENGINE + ")");
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) SELECT i, 'it-508-' || i, 'it-508-' || i || '@example.com', 'x', true "
                + "FROM generate_series(508001, 508003) i");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + TEACHER + ", id FROM roles WHERE name = 'TEACHER'");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + STUDENT + ", id FROM roles WHERE name = 'STUDENT'");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + ENGINE + ", id FROM roles WHERE name = 'INTEGRATION'");
        sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, created_by, visible, needs_review, source) VALUES ("
                + STATEMENT + ", 'Prova de re-correcao #508', 'TEST', 60, 6, " + TEACHER + ", false, true, 'manual')");
        sql("INSERT INTO questions (id, statement_id, number, text, question_type, max_score, order_index) VALUES ("
                + QUESTION + ", " + STATEMENT + ", 1, 'Explique a diferenca entre local e global.', 'open', 6, 1)");
        teacherToken = jwtService.generateToken(TEACHER, List.of("TEACHER"));
        studentToken = jwtService.generateToken(STUDENT, List.of("STUDENT"));
        engineToken = jwtService.generateToken(ENGINE, List.of("INTEGRATION"));
    }

    @Test
    void anAnswerIsGradedOnceAndThenRectifiedWithTheFinalScoreRecomputed() {
        bearer(teacherToken).post().uri("/api/v1/statements/" + STATEMENT + "/approve").exchange().expectStatus().isOk();
        bearer(teacherToken).patch().uri("/api/v1/statements/" + STATEMENT + "/visibility?visible=true").exchange().expectStatus().isOk();

        AtomicLong simulation = new AtomicLong();
        bearer(studentToken).post().uri("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + STUDENT + ",\"statementId\":" + STATEMENT + "}").exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.id").value(value -> simulation.set(((Number) value).longValue()));
        AtomicLong answer = new AtomicLong();
        bearer(studentToken).post().uri("/api/v1/simulation-answers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"simulationId\":" + simulation.get() + ",\"questionId\":" + QUESTION + ",\"answerText\":\"A local vive na funcao.\"}")
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.id").value(value -> answer.set(((Number) value).longValue()));
        bearer(studentToken).post().uri("/api/v1/simulations/" + simulation.get() + "/submit").exchange().expectStatus().isOk();
        String grade = "/api/v1/simulation-answers/" + answer.get() + "/grade";

        // rectificar o que ainda não foi corrigido não é permitido; um aluno nunca corrige
        patch(engineToken, grade + "?rectify=true", 5.0).expectStatus().isEqualTo(409);
        patch(studentToken, grade, 5.0).expectStatus().isForbidden();

        // primeira correção (a do professor) e nota final da simulação
        patch(engineToken, grade, 3.0).expectStatus().isOk().expectBody().jsonPath("$.scoreObtained").isEqualTo(3.0);
        assertThat(finalScore(simulation.get())).isEqualTo(3.0);
        // corrigir outra vez sem dizer que é uma rectificação continua recusado
        patch(engineToken, grade, 4.0).expectStatus().isEqualTo(409);

        // rectificação: sobe a pontuação e a nota final acompanha
        patch(engineToken, grade + "?rectify=true", 5.0).expectStatus().isOk().expectBody().jsonPath("$.scoreObtained").isEqualTo(5.0);
        assertThat(finalScore(simulation.get())).isEqualTo(5.0);
        // repetir o mesmo valor não duplica nada (a reentrega do job)
        patch(engineToken, grade + "?rectify=true", 5.0).expectStatus().isOk();
        assertThat(finalScore(simulation.get())).isEqualTo(5.0);
        // nunca acima da cotação da questão; um aluno nunca rectifica
        patch(engineToken, grade + "?rectify=true", 7.0).expectStatus().isBadRequest();
        patch(studentToken, grade + "?rectify=true", 6.0).expectStatus().isForbidden();
        assertThat(finalScore(simulation.get())).isEqualTo(5.0);
    }

    private WebTestClient.ResponseSpec patch(String token, String uri, double score) {
        return bearer(token).patch().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"scoreObtained\":" + score + "}").exchange();
    }

    private double finalScore(long simulation) {
        double[] score = new double[1];
        bearer(studentToken).get().uri("/api/v1/simulations/" + simulation).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.finalScore").value(value -> score[0] = ((Number) value).doubleValue());
        return score[0];
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
