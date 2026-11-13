package ao.creativemode.kixi.moderation;

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

/** A fila, a decisão e a auditoria da moderação de ponta a ponta, contra PostgreSQL (kixi#591). */
@SpringBootTest(properties = {
        "spring.r2dbc.pool.max-size=2",
        "spring.r2dbc.pool.initial-size=0",
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        // A sessão revalida-se a cada 1 ms em vez de 30 s, para o teste da suspensão não ter de esperar (kixi#450).
        "app.jwt.revalidation-ms=1",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
class ModerationPostgresIntegrationTest {

    private static final long ADMIN = 591001L;
    private static final long STUDENT = 591002L;       // denunciante
    private static final long REPORTED = 591003L;      // a pessoa denunciada
    private static final long INSTITUTION = 591001L;
    private static final long ACCOUNT_REPORT = 591001L;
    private static final long SCHOOL_REPORT = 591002L;
    private static final String NOTE = "Faz-se passar por um professor da escola.";

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private JwtService jwtService;

    private WebTestClient client;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("ModerationPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) SELECT i, 'it-591-' || i, 'it-591-' || i || '@example.com', 'x', true "
                + "FROM generate_series(591001, 591003) i");
        // O filtro de sessão confere os papéis do token com os da base: quem se faz passar por professor ou coordenador
        // tem de os ter lá, para o 403 vir da regra e não de uma sessão revogada.
        role(ADMIN, "ADMIN");
        for (String role : List.of("STUDENT", "TEACHER", "COORDINATOR")) role(STUDENT, role);
        role(REPORTED, "STUDENT");
        sql("INSERT INTO users (account_id, first_name, last_name) VALUES (" + ADMIN + ", 'Ana', 'Admin'), (" + STUDENT
                + ", 'Sara', 'Aluna'), (" + REPORTED + ", 'Rui', 'Denunciado')");
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (" + INSTITUTION + ", 'IT591', 'Escola 591', now(), now())");
        sql("INSERT INTO moderation_reports (id, target_type, target_account_id, reporter_account_id, reason, note) VALUES ("
                + ACCOUNT_REPORT + ", 'ACCOUNT', " + REPORTED + ", " + STUDENT + ", 'IMPERSONATION', 'finge ser professor')");
        sql("INSERT INTO moderation_reports (id, target_type, target_institution_id, reporter_account_id, reason, note) VALUES ("
                + SCHOOL_REPORT + ", 'INSTITUTION', " + INSTITUTION + ", " + STUDENT + ", 'FAKE_SCHOOL', '')");
    }

    @AfterEach
    void clean() {
        sql("ALTER TABLE moderation_audit DROP CONSTRAINT IF EXISTS it_591_audit_down");
        sql("DELETE FROM moderation_audit WHERE report_id IN (" + ACCOUNT_REPORT + ", " + SCHOOL_REPORT + ")");
        sql("DELETE FROM moderation_reports WHERE id IN (" + ACCOUNT_REPORT + ", " + SCHOOL_REPORT + ")");
        sql("DELETE FROM institutions WHERE id = " + INSTITUTION);
        sql("DELETE FROM account_roles WHERE account_id BETWEEN 591001 AND 591003");
        sql("DELETE FROM users WHERE account_id BETWEEN 591001 AND 591003");
        sql("DELETE FROM accounts WHERE id BETWEEN 591001 AND 591003");
    }

    @Test
    void theQueueListsWhatIsOpenWithNamesAndTheDecisionLeavesItAndTheAudit() {
        String admin = token(ADMIN, "ADMIN");

        bearer(admin).get().uri("/api/v1/moderation/reports?status=OPEN").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[?(@.id==" + ACCOUNT_REPORT + ")].targetName").isEqualTo("Rui Denunciado")
                .jsonPath("$[?(@.id==" + ACCOUNT_REPORT + ")].reporterName").isEqualTo("Sara Aluna")
                .jsonPath("$[?(@.id==" + SCHOOL_REPORT + ")].targetName").isEqualTo("Escola 591");

        bearer(admin).post().uri("/api/v1/moderation/reports/" + ACCOUNT_REPORT + "/suspend")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"" + NOTE + "\"}")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.status").isEqualTo("SUSPENDED")
                .jsonPath("$.decidedBy").isEqualTo("Ana Admin")
                .jsonPath("$.decisionNote").isEqualTo(NOTE);

        assertThat(count("SELECT COUNT(*) FROM accounts WHERE id = " + REPORTED + " AND active = FALSE")).isEqualTo(1);
        bearer(admin).get().uri("/api/v1/moderation/reports?status=OPEN").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1);
        bearer(admin).get().uri("/api/v1/moderation/reports?status=SUSPENDED").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1);
        bearer(admin).get().uri("/api/v1/moderation/audit").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$[0].actor").isEqualTo("Ana Admin")
                .jsonPath("$[0].action").isEqualTo("SUSPENDED")
                .jsonPath("$[0].targetName").isEqualTo("Rui Denunciado")
                .jsonPath("$[0].reportId").isEqualTo((int) ACCOUNT_REPORT)
                .jsonPath("$[0].note").isEqualTo(NOTE);

        // decidir outra vez a mesma denúncia: 409, e a auditoria não ganha uma segunda linha
        bearer(admin).post().uri("/api/v1/moderation/reports/" + ACCOUNT_REPORT + "/ignore")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"" + NOTE + "\"}").exchange().expectStatus().isEqualTo(409);
        assertThat(count("SELECT COUNT(*) FROM moderation_audit WHERE report_id = " + ACCOUNT_REPORT)).isEqualTo(1);
    }

    @Test
    void hidingASchoolTakesItOutOfSightAndItsRulesHold() {
        String admin = token(ADMIN, "ADMIN");

        bearer(admin).post().uri("/api/v1/moderation/reports/" + SCHOOL_REPORT + "/suspend")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"" + NOTE + "\"}")
                .exchange().expectStatus().isBadRequest();
        bearer(admin).post().uri("/api/v1/moderation/reports/" + SCHOOL_REPORT + "/hide")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"curta\"}")
                .exchange().expectStatus().isBadRequest();
        assertThat(count("SELECT COUNT(*) FROM institutions WHERE id = " + INSTITUTION + " AND deleted_at IS NULL")).isEqualTo(1);

        bearer(admin).post().uri("/api/v1/moderation/reports/" + SCHOOL_REPORT + "/hide")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"" + NOTE + "\"}")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("HIDDEN");
        assertThat(count("SELECT COUNT(*) FROM institutions WHERE id = " + INSTITUTION + " AND deleted_at IS NOT NULL")).isEqualTo(1);
        // o nome da escola continua legível na fila e na auditoria, mesmo oculta
        bearer(admin).get().uri("/api/v1/moderation/audit").exchange().expectBody()
                .jsonPath("$[0].targetName").isEqualTo("Escola 591");
    }

    @Test
    void ignoringChangesNothingButIsAudited() {
        bearer(token(ADMIN, "ADMIN")).post().uri("/api/v1/moderation/reports/" + ACCOUNT_REPORT + "/ignore")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"" + NOTE + "\"}")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("IGNORED");
        assertThat(count("SELECT COUNT(*) FROM accounts WHERE id = " + REPORTED + " AND active = TRUE")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM moderation_audit WHERE report_id = " + ACCOUNT_REPORT + " AND action = 'IGNORED'")).isEqualTo(1);
    }

    @Test
    void aFailureWritingTheAuditUndoesTheDecisionAndTheEffect() {
        // Uma restrição que recusa toda a linha nova da auditoria (NOT VALID: não olha para o que já lá está).
        sql("ALTER TABLE moderation_audit ADD CONSTRAINT it_591_audit_down CHECK (false) NOT VALID");

        bearer(token(ADMIN, "ADMIN")).post().uri("/api/v1/moderation/reports/" + ACCOUNT_REPORT + "/suspend")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"" + NOTE + "\"}")
                .exchange().expectStatus().isEqualTo(409);

        assertThat(count("SELECT COUNT(*) FROM moderation_reports WHERE id = " + ACCOUNT_REPORT + " AND status = 'OPEN'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM accounts WHERE id = " + REPORTED + " AND active = TRUE")).isEqualTo(1);
    }

    @Test
    void onlyTheAdministratorEntersTheModeration() {
        for (String role : List.of("TEACHER", "COORDINATOR", "STUDENT")) {
            bearer(token(STUDENT, role)).get().uri("/api/v1/moderation/reports").exchange().expectStatus().isForbidden();
            bearer(token(STUDENT, role)).get().uri("/api/v1/moderation/audit").exchange().expectStatus().isForbidden();
            bearer(token(STUDENT, role)).post().uri("/api/v1/moderation/reports/" + ACCOUNT_REPORT + "/ignore")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"\"}").exchange().expectStatus().isForbidden();
        }
        assertThat(count("SELECT COUNT(*) FROM moderation_reports WHERE status = 'OPEN' AND id IN (" + ACCOUNT_REPORT + ", " + SCHOOL_REPORT + ")")).isEqualTo(2);
    }

    @Test
    void aSuspendedAccountLosesTheTokenItAlreadyHeld() throws InterruptedException {
        String held = token(REPORTED, "STUDENT");
        // Antes: o token vale (a conta autentica e a rota de moderação recusa-lhe o papel, 403).
        bearer(held).get().uri("/api/v1/moderation/reports").exchange().expectStatus().isForbidden();

        bearer(token(ADMIN, "ADMIN")).post().uri("/api/v1/moderation/reports/" + ACCOUNT_REPORT + "/suspend")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"" + NOTE + "\"}")
                .exchange().expectStatus().isOk();
        Thread.sleep(20);

        // Depois: o mesmo token, emitido antes da suspensão, já não autentica (o filtro de sessão lê accounts.active).
        bearer(held).get().uri("/api/v1/moderation/reports").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void theQueueAndTheAuditArePaged() {
        String admin = token(ADMIN, "ADMIN");
        bearer(admin).post().uri("/api/v1/moderation/reports/" + ACCOUNT_REPORT + "/ignore")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"" + NOTE + "\"}").exchange().expectStatus().isOk();
        bearer(admin).post().uri("/api/v1/moderation/reports/" + SCHOOL_REPORT + "/hide")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"" + NOTE + "\"}").exchange().expectStatus().isOk();

        bearer(admin).get().uri("/api/v1/moderation/reports?limit=1").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1);
        bearer(admin).get().uri("/api/v1/moderation/reports?limit=1&offset=1").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1);
        bearer(admin).get().uri("/api/v1/moderation/reports?offset=2").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(0);
        bearer(admin).get().uri("/api/v1/moderation/audit?limit=1").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1);
        bearer(admin).get().uri("/api/v1/moderation/audit?limit=1&offset=1").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].action").value(a -> assertThat(a).isIn("IGNORED", "HIDDEN"));
    }

    private long count(String query) {
        Long value = database.sql(query).map((row, meta) -> row.get(0, Long.class)).one().block(Duration.ofSeconds(10));
        return value == null ? 0 : value;
    }

    private void role(long account, String role) {
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + account + ", id FROM roles WHERE name = '" + role + "'");
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
