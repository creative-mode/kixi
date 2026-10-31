package ao.creativemode.kixi.exams.service;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Um enunciado de prova de sala só é visível ao aluno depois da prova terminada e corrigida (kixi#519): a sala retém-no,
 * fechar a sala com respostas por rever não o liberta, corrigir a última sim, o professor pode mostrá-lo mais cedo, e um
 * enunciado sem sala fica como estava. As salas continuam a funcionar com o enunciado retido.
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
class StatementEmbargoPostgresIntegrationTest {

    private static final long TEACHER = 519001L;
    private static final long STUDENT = 519002L;
    private static final long WITH_ROOM = 519001L;
    private static final long FREE = 519002L;
    private static final long ROOM = 519001L;
    private static final long SIMULATION = 519001L;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private JwtService jwtService;

    private WebTestClient client;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("StatementEmbargoPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) SELECT i, 'it-519-' || i, 'it-519-' || i || '@example.com', 'x', true "
                + "FROM generate_series(519001, 519002) i");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + TEACHER + ", id FROM roles WHERE name = 'TEACHER'");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + STUDENT + ", id FROM roles WHERE name = 'STUDENT'");
        for (long id : new long[] {WITH_ROOM, FREE}) {
            sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, created_by, visible, needs_review, source) VALUES ("
                    + id + ", 'Prova it-519 #" + id + "', 'TEST', 60, 5, " + TEACHER + ", true, false, 'manual')");
        }
    }

    @org.junit.jupiter.api.AfterEach
    void clean() {
        sql("DELETE FROM simulation_answers WHERE simulation_id = " + SIMULATION);
        sql("DELETE FROM simulations WHERE id = " + SIMULATION);
        sql("DELETE FROM exam_rooms WHERE id = " + ROOM);
        sql("DELETE FROM statements WHERE id IN (" + WITH_ROOM + ", " + FREE + ")");
        sql("DELETE FROM account_roles WHERE account_id IN (" + TEACHER + ", " + STUDENT + ")");
        sql("DELETE FROM accounts WHERE id IN (" + TEACHER + ", " + STUDENT + ")");
    }

    @Test
    void theRoomHoldsTheStatementUntilItIsClosedAndEveryAnswerIsGraded() {
        assertThat(visible(WITH_ROOM)).isTrue();
        // sem sala: nada muda
        assertThat(visible(FREE)).isTrue();

        sql("INSERT INTO exam_rooms (id, statement_id, teacher_account_id, starts_at, ends_at, duration_minutes, status) VALUES ("
                + ROOM + ", " + WITH_ROOM + ", " + TEACHER + ", now(), now() + interval '1 day', 60, 'RUNNING')");
        assertThat(visible(WITH_ROOM)).as("a sala retém o enunciado").isFalse();
        assertThat(release(WITH_ROOM)).isTrue();
        assertThat(visible(FREE)).as("outro enunciado sem sala fica visível").isTrue();
        // o aluno não o lê enquanto está retido
        bearer(token(STUDENT, "STUDENT")).get().uri("/api/v1/statements/" + WITH_ROOM).exchange().expectStatus().is4xxClientError();

        sql("INSERT INTO simulations (id, account_id, statement_id, status, exam_room_id) VALUES (" + SIMULATION + ", " + STUDENT + ", "
                + WITH_ROOM + ", 'FINISHED', " + ROOM + ")");
        sql("INSERT INTO questions (id, statement_id, number, text, question_type, max_score, order_index) VALUES (519001, " + WITH_ROOM
                + ", 1, 'q', 'open', 5, 1) ON CONFLICT DO NOTHING");
        sql("INSERT INTO simulation_answers (simulation_id, question_id, answer_text, review_status) VALUES (" + SIMULATION + ", 519001, 'x', 'PENDING_REVIEW')");

        // sala fechada mas com uma resposta por rever: continua retido
        sql("UPDATE exam_rooms SET status = 'CLOSED' WHERE id = " + ROOM);
        assertThat(visible(WITH_ROOM)).as("fechada mas por corrigir").isFalse();

        // a última resposta corrigida liberta o enunciado, e a intenção de publicar limpa-se
        sql("UPDATE simulation_answers SET review_status = 'GRADED' WHERE simulation_id = " + SIMULATION);
        assertThat(visible(WITH_ROOM)).as("terminada e corrigida").isTrue();
        assertThat(release(WITH_ROOM)).isFalse();
        sql("DELETE FROM simulation_answers WHERE simulation_id = " + SIMULATION);
        sql("DELETE FROM questions WHERE id = 519001");
    }

    @Test
    void theTeacherCanShowItEarlierAndADraftRoomKeepsItHeld() {
        sql("INSERT INTO exam_rooms (id, statement_id, teacher_account_id, starts_at, ends_at, duration_minutes, status) VALUES ("
                + ROOM + ", " + WITH_ROOM + ", " + TEACHER + ", now(), now() + interval '1 day', 60, 'DRAFT')");
        assertThat(visible(WITH_ROOM)).isFalse();
        // o que o endpoint de visibilidade faz à linha: visible passa a verdadeiro (as regras de gabarito são do endpoint, não da retenção)
        sql("UPDATE statements SET visible = TRUE WHERE id = " + WITH_ROOM);
        assertThat(visible(WITH_ROOM)).as("mostrado mais cedo pelo professor").isTrue();
        assertThat(release(WITH_ROOM)).as("a retenção acaba").isFalse();
    }

    private boolean visible(long statement) {
        return Boolean.TRUE.equals(database.sql("SELECT visible FROM statements WHERE id = " + statement)
                .map((row, meta) -> row.get(0, Boolean.class)).one().block(Duration.ofSeconds(10)));
    }

    private boolean release(long statement) {
        return Boolean.TRUE.equals(database.sql("SELECT release_after_exam FROM statements WHERE id = " + statement)
                .map((row, meta) -> row.get(0, Boolean.class)).one().block(Duration.ofSeconds(10)));
    }

    private String token(long account, String role) {
        return jwtService.generateToken(account, List.of(role));
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
