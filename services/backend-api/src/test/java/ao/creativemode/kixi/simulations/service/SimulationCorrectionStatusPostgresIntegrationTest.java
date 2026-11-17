package ao.creativemode.kixi.simulations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import ao.creativemode.kixi.identity.service.JwtService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
 * O estado da correção e a nota consolidada numa simulação LIVRE, sem sala de plano (kixi#609): o motor grava a nota da IA de
 * cada aberta com {@code PUT /simulation-answers/{id}/ai-grade} (a conta INTEGRATION pode, também fora dos planos), o estado
 * passa de PENDING a IN_PROGRESS e a DONE, e quando a última aberta é corrigida a nota, o cartão do feed e o XP acompanham, para o
 * histórico, o cartão e o ecrã de resultado mostrarem a mesma nota.
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
class SimulationCorrectionStatusPostgresIntegrationTest {

    private static final long ADMIN = 609001L;
    private static final long STUDENT = 609002L;
    private static final long OTHER_STUDENT = 609003L;
    private static final long ENGINE = 609004L;
    private static final String ACCOUNTS = "609001, 609002, 609003, 609004";

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private JwtService jwtService;

    private WebTestClient client;
    private long openOne;
    private long openTwo;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("SimulationCorrectionStatusPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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
        clean();
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) SELECT i, 'it-609-' || i, 'it-609-' || i || '@example.com', 'x', true "
                + "FROM generate_series(609001, 609004) i");
        role(ADMIN, "ADMIN");
        role(STUDENT, "STUDENT");
        role(OTHER_STUDENT, "STUDENT");
        role(ENGINE, "INTEGRATION");
        sql("INSERT INTO subjects (id, code, name, created_at, updated_at) VALUES (609001, 'M609', 'Matemática', now(), now())");
        // uma prova livre: sem turma nem sala, com uma objectiva (5) e duas abertas (6 e 4)
        sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, created_by, visible, needs_review, source, subject_id) VALUES "
                + "(609001, 'Treino livre #609', 'TEST', 60, 15, " + ADMIN + ", true, false, 'manual', 609001)");
        sql("INSERT INTO questions (id, statement_id, number, text, question_type, max_score, order_index) VALUES "
                + "(609001, 609001, 1, 'Quanto é 2 + 3?', 'multiple_choice', 5, 1), "
                + "(609002, 609001, 2, 'Explique a diferença entre local e global.', 'open', 6, 2), "
                + "(609003, 609001, 3, 'Defina o que é um algoritmo.', 'open', 4, 3)");
        sql("INSERT INTO simulations (id, account_id, statement_id, status, final_score) VALUES (609001, " + STUDENT + ", 609001, 'FINISHED', 5)");
        sql("INSERT INTO simulation_answers (simulation_id, question_id, answer_text, review_status, score_obtained, is_correct) VALUES "
                + "(609001, 609001, NULL, 'AUTO_GRADED', 5, true)");
        sql("INSERT INTO simulation_answers (simulation_id, question_id, answer_text, review_status) VALUES "
                + "(609001, 609002, 'A local vive na função.', 'PENDING_REVIEW'), (609001, 609003, 'Passos para resolver um problema.', 'PENDING_REVIEW')");
        openOne = scalar("SELECT id FROM simulation_answers WHERE simulation_id = 609001 AND question_id = 609002");
        openTwo = scalar("SELECT id FROM simulation_answers WHERE simulation_id = 609001 AND question_id = 609003");
        // o aluno publicou o resultado no feed: o cartão guarda a nota e as abertas por corrigir
        sql("INSERT INTO posts (author_account_id, institution_id, school_year_id, course_id, class_id, type, content, simulation_id, final_score, max_score, "
                + "pending_review_count) VALUES (" + STUDENT + ", 609001, 609001, 609001, 609001, 'simulation', 'O meu resultado', 609001, 5, 15, 2)");
    }

    @AfterEach
    void clean() {
        sql("DELETE FROM posts WHERE simulation_id IN (609001, 609002)");
        sql("DELETE FROM simulation_xp_awards WHERE simulation_id IN (609001, 609002)");
        sql("DELETE FROM simulation_answers WHERE simulation_id IN (609001, 609002)");
        sql("DELETE FROM simulations WHERE id IN (609001, 609002)");
        sql("DELETE FROM questions WHERE statement_id = 609001");
        sql("DELETE FROM statements WHERE id = 609001");
        sql("DELETE FROM subjects WHERE id = 609001");
        sql("DELETE FROM account_roles WHERE account_id IN (" + ACCOUNTS + ")");
        sql("DELETE FROM accounts WHERE id IN (" + ACCOUNTS + ")");
    }

    @Test
    void theCorrectionStatusFollowsTheAiAndTheFinalGradeIsTheSameEverywhere() {
        String student = token(STUDENT, "STUDENT");
        String engine = token(ENGINE, "INTEGRATION");

        // nada corrigido: PENDING, com a nota provisória só das objectivas
        simulation(student).jsonPath("$.status").isEqualTo("FINISHED").jsonPath("$.correctionStatus").isEqualTo("PENDING")
                .jsonPath("$.correctedAt").doesNotExist().jsonPath("$.finalScore").isEqualTo(5.0);
        result(student).jsonPath("$.correctionStatus").isEqualTo("PENDING").jsonPath("$.pendingReviewCount").isEqualTo(2);

        // a conta do motor corrige a primeira aberta (e pode, mesmo numa simulação livre, sem sala de plano)
        aiGrade(engine, openOne, "{\"scoreObtained\":4.5,\"feedback\":\"Falta o tempo de vida.\",\"level\":\"PARCIAL\",\"confidence\":0.9}").expectStatus().isOk()
                .expectBody().jsonPath("$.reviewStatus").isEqualTo("AI_GRADED");
        simulation(student).jsonPath("$.correctionStatus").isEqualTo("IN_PROGRESS").jsonPath("$.correctedAt").doesNotExist()
                .jsonPath("$.finalScore").isEqualTo(9.5);
        result(student).jsonPath("$.correctionStatus").isEqualTo("IN_PROGRESS").jsonPath("$.pendingReviewCount").isEqualTo(1).jsonPath("$.finalScore").isEqualTo(9.5);
        assertThat(scalar("SELECT pending_review_count FROM posts WHERE simulation_id = 609001")).isEqualTo(1L);
        assertThat(decimal("SELECT final_score FROM posts WHERE simulation_id = 609001")).isEqualTo(9.5);
        assertThat(decimal("SELECT score_percent FROM simulation_xp_awards WHERE simulation_id = 609001")).isEqualTo(63.33);

        // o motor avisa que não consegue corrigir a outra: FAILED até a IA voltar a corrigir
        failure(engine, 609001, "{\"reason\":\"A IA ficou indisponível depois das tentativas.\"}").expectStatus().isNoContent();
        simulation(student).jsonPath("$.correctionStatus").isEqualTo("FAILED");
        result(student).jsonPath("$.correctionStatus").isEqualTo("FAILED");
        assertThat(text("SELECT correction_failure_reason FROM simulations WHERE id = 609001")).contains("indisponível");

        // a última aberta é corrigida: DONE, o aviso de falha desaparece e a nota consolida-se em todo o lado
        aiGrade(engine, openTwo, "{\"scoreObtained\":3.0,\"feedback\":\"Correcta.\",\"level\":\"PARCIAL\",\"confidence\":0.8}").expectStatus().isOk();
        simulation(student).jsonPath("$.correctionStatus").isEqualTo("DONE").jsonPath("$.correctedAt").exists().jsonPath("$.finalScore").isEqualTo(12.5);
        result(student).jsonPath("$.correctionStatus").isEqualTo("DONE").jsonPath("$.correctedAt").exists()
                .jsonPath("$.pendingReviewCount").isEqualTo(0).jsonPath("$.finalScore").isEqualTo(12.5);
        assertThat(text("SELECT COALESCE(correction_failed_at::text, 'limpo') FROM simulations WHERE id = 609001")).isEqualTo("limpo");
        // o cartão do feed, o XP e o histórico dizem o mesmo que o ecrã de resultado
        assertThat(decimal("SELECT final_score FROM posts WHERE simulation_id = 609001")).isEqualTo(12.5);
        assertThat(scalar("SELECT pending_review_count FROM posts WHERE simulation_id = 609001")).isEqualTo(0L);
        assertThat(decimal("SELECT final_score FROM simulations WHERE id = 609001")).isEqualTo(12.5);
        assertThat(decimal("SELECT score_percent FROM simulation_xp_awards WHERE simulation_id = 609001")).isEqualTo(83.33);
        // sem abertas por corrigir, a simulação já pode entrar no ranking (o ranking exclui as que ainda têm pendentes)
        assertThat(scalar("SELECT COUNT(*) FROM simulation_answers WHERE simulation_id = 609001 AND review_status = 'PENDING_REVIEW'")).isZero();
    }

    @Test
    void onlyTheEngineAndTheAdministratorReportAFailureAndOnlyOnAFinishedSimulation() {
        String student = token(STUDENT, "STUDENT");
        String body = "{\"reason\":\"falhou\"}";
        failure(student, 609001, body).expectStatus().isForbidden();
        failure(token(OTHER_STUDENT, "STUDENT"), 609001, body).expectStatus().isForbidden();
        failure(token(ADMIN, "ADMIN"), 609001, body).expectStatus().isNoContent();
        simulation(student).jsonPath("$.correctionStatus").isEqualTo("FAILED");
        failure(token(ENGINE, "INTEGRATION"), 609001, "{\"reason\":\" \"}").expectStatus().isBadRequest();
        failure(token(ENGINE, "INTEGRATION"), 609999, body).expectStatus().isNotFound();
        // uma simulação ainda por entregar não tem correção que possa falhar
        sql("INSERT INTO simulations (id, account_id, statement_id, status) VALUES (609002, " + STUDENT + ", 609001, 'IN_PROGRESS')");
        failure(token(ENGINE, "INTEGRATION"), 609002, body).expectStatus().isEqualTo(409);
    }

    private WebTestClient.BodyContentSpec simulation(String token) {
        return bearer(token).get().uri("/api/v1/simulations/609001").exchange().expectStatus().isOk().expectBody();
    }

    private WebTestClient.BodyContentSpec result(String token) {
        return bearer(token).get().uri("/api/v1/simulations/609001/result").exchange().expectStatus().isOk().expectBody();
    }

    private WebTestClient.ResponseSpec aiGrade(String token, long answer, String body) {
        return bearer(token).put().uri("/api/v1/simulation-answers/" + answer + "/ai-grade").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec failure(String token, long simulation, String body) {
        return bearer(token).put().uri("/api/v1/simulations/" + simulation + "/correction-failure").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private WebTestClient bearer(String token) {
        return client.mutate().defaultHeaders(headers -> headers.setBearerAuth(token)).build();
    }

    private String token(long account, String role) {
        return jwtService.generateToken(account, List.of(role));
    }

    private void role(long account, String role) {
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + account + ", id FROM roles WHERE name = '" + role + "'");
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(10));
    }

    private long scalar(String query) {
        Long value = database.sql(query).map((row, meta) -> row.get(0, Long.class)).one().block(Duration.ofSeconds(10));
        return value == null ? 0 : value;
    }

    private double decimal(String query) {
        Double value = database.sql(query).map((row, meta) -> row.get(0, Double.class)).one().block(Duration.ofSeconds(10));
        return value == null ? Double.NaN : Math.round(value * 100.0) / 100.0;
    }

    private String text(String query) {
        return database.sql(query).map((row, meta) -> row.get(0, String.class)).one().block(Duration.ofSeconds(10));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
