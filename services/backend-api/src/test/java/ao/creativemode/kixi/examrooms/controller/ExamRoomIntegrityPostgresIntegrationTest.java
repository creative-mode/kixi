package ao.creativemode.kixi.examrooms.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import com.fasterxml.jackson.databind.JsonNode;
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

import ao.creativemode.kixi.identity.service.JwtService;

/**
 * A integridade de uma sala contra um PostgreSQL a sério (kixi#595): o endpoint
 * devolve o nome de cada aluno e nunca o número da conta como nome, mesmo quando
 * a conta foi apagada e só o perfil ficou para trás.
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
class ExamRoomIntegrityPostgresIntegrationTest {

    private static final long TEACHER = 595001L;
    private static final long OTHER_TEACHER = 595002L;
    private static final long NAMED = 595003L;
    private static final long USERNAME_ONLY = 595004L;
    private static final long DELETED = 595005L;
    private static final long ADMIN = 595006L;
    private static final long STATEMENT = 595001L;
    private static final long ROOM = 595001L;
    private static final long SIMULATION = 595001L;
    private static final long[] ACCOUNTS = {TEACHER, OTHER_TEACHER, NAMED, USERNAME_ONLY, DELETED, ADMIN};
    private static final String ALL_ACCOUNTS = String.join(",", java.util.Arrays.stream(ACCOUNTS)
            .mapToObj(Long::toString).toList());

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DatabaseClient db;

    @Autowired
    private JwtService jwt;

    private WebTestClient client;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException(
                    "ExamRoomIntegrityPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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
                .configureClient().responseTimeout(Duration.ofSeconds(30)).build();
        clean();
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, active) SELECT i, "
                + "'it-595-' || i, 'it-595-' || i || '@example.com', 'x', true, true "
                + "FROM unnest(ARRAY[" + ALL_ACCOUNTS + "]::bigint[]) i");
        role(TEACHER, "TEACHER");
        role(OTHER_TEACHER, "TEACHER");
        role(NAMED, "STUDENT");
        role(USERNAME_ONLY, "STUDENT");
        role(DELETED, "STUDENT");
        role(ADMIN, "ADMIN");

        // O perfil de DELETED sobrevive à conta: não pode servir de nome.
        sql("INSERT INTO users (account_id, first_name, last_name) VALUES ("
                + NAMED + ", 'Ana', 'Silva'), (" + DELETED + ", 'Fantasma', 'Apagado')");
        sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, created_by, visible, needs_review, source) VALUES ("
                + STATEMENT + ", 'Prova it-595', 'TEST', 90, 20, " + TEACHER + ", true, false, 'manual')");
        sql("INSERT INTO exam_rooms (id, statement_id, teacher_account_id, starts_at, ends_at, duration_minutes, status) VALUES ("
                + ROOM + ", " + STATEMENT + ", " + TEACHER + ", now() - interval '2 hours', now() - interval '1 hour', 60, 'CLOSED')");
        sql("INSERT INTO exam_room_participants (exam_room_id, account_id) VALUES ("
                + "(" + ROOM + ", " + NAMED + "), (" + ROOM + ", " + USERNAME_ONLY + "), (" + ROOM + ", " + DELETED + ")");
        sql("INSERT INTO simulations (id, account_id, statement_id, status, exam_room_id) VALUES ("
                + SIMULATION + ", " + NAMED + ", " + STATEMENT + ", 'FINISHED', " + ROOM + ")");
        sql("UPDATE exam_room_participants SET simulation_id = " + SIMULATION
                + " WHERE exam_room_id = " + ROOM + " AND account_id = " + NAMED);
        sql("INSERT INTO simulation_integrity_events (simulation_id, kind, occurred_at) VALUES ("
                + SIMULATION + ", 'TAB_HIDDEN', now())");
        sql("UPDATE accounts SET deleted_at = now() WHERE id = " + DELETED);
    }

    @AfterEach
    void clean() {
        sql("DELETE FROM exam_room_participants WHERE exam_room_id = " + ROOM);
        sql("DELETE FROM simulations WHERE exam_room_id = " + ROOM);
        sql("DELETE FROM exam_rooms WHERE id = " + ROOM);
        sql("DELETE FROM statements WHERE id = " + STATEMENT);
        sql("DELETE FROM users WHERE account_id IN (" + ALL_ACCOUNTS + ")");
        sql("DELETE FROM user_stats WHERE account_id IN (" + ALL_ACCOUNTS + ")");
        sql("DELETE FROM account_roles WHERE account_id IN (" + ALL_ACCOUNTS + ")");
        sql("DELETE FROM accounts WHERE id IN (" + ALL_ACCOUNTS + ")");
    }

    @Test
    void returnsTheNameOfEachStudentAndNeverTheAccountId() {
        JsonNode body = json(get(TEACHER, "TEACHER", "/api/v1/exam-rooms/" + ROOM + "/integrity")
                .expectStatus().isOk());

        assertThat(body.get("roomId").asLong()).isEqualTo(ROOM);
        JsonNode participants = body.get("participants");
        assertThat(participants).hasSize(3);

        JsonNode named = participant(participants, NAMED);
        assertThat(named.get("displayName").asText()).isEqualTo("Ana Silva");
        assertThat(named.get("events").get(0).get("kind").asText()).isEqualTo("TAB_HIDDEN");

        JsonNode usernameOnly = participant(participants, USERNAME_ONLY);
        assertThat(usernameOnly.get("displayName").asText()).isEqualTo("it-595-" + USERNAME_ONLY);

        JsonNode deleted = participant(participants, DELETED);
        assertThat(deleted.get("displayName").asText()).isEqualTo("Aluno sem conta");

        for (JsonNode participant : participants) {
            assertThat(participant.get("displayName").asText())
                    .as("o nome de %s nunca é o número da conta", participant.get("accountId").asLong())
                    .isNotEqualTo(participant.get("accountId").asText());
        }
    }

    @Test
    void onlyTheRoomStaffCanReadTheNames() {
        get(ADMIN, "ADMIN", "/api/v1/exam-rooms/" + ROOM + "/integrity")
                .expectStatus().isOk()
                .expectBody().jsonPath("$.participants[0].displayName").isNotEmpty();
        get(OTHER_TEACHER, "TEACHER", "/api/v1/exam-rooms/" + ROOM + "/integrity")
                .expectStatus().isForbidden();
    }

    private static JsonNode participant(JsonNode participants, long accountId) {
        for (JsonNode participant : participants) {
            if (participant.get("accountId").asLong() == accountId) return participant;
        }
        throw new AssertionError("participant " + accountId + " missing");
    }

    private JsonNode json(WebTestClient.ResponseSpec spec) {
        return spec.expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec get(long account, String role, String uri) {
        String token = jwt.generateToken(account, List.of(role));
        return client.mutate().defaultHeaders(headers -> headers.setBearerAuth(token)).build()
                .get().uri(uri).accept(MediaType.APPLICATION_JSON).exchange();
    }

    private void role(long account, String role) {
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + account + ", id FROM roles WHERE name = '" + role + "'");
    }

    private void sql(String statement) {
        db.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(20));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
