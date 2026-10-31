package ao.creativemode.kixi.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
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
 * A ligação ao motor (kixi#525), contra PostgreSQL e um motor falso: o plano pronto abre a pauta com o pedido do contrato v1
 * (turma, alunos, provas P1 e P2) e encerra as provas; um plano por concluir não abre; os eventos entram só com assinatura válida.
 */
@SpringBootTest(properties = {
        "spring.r2dbc.pool.max-size=2",
        "spring.r2dbc.pool.initial-size=0",
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test",
        "schoolops.enabled=true",
        "schoolops.token=token-de-teste",
        "schoolops.callback-url=https://kixi.example/api/v1/integrations/schoolops/events",
        "schoolops.poll-ms=86400000",
        "schoolops.events-secret=whsec_c2VncmVkby1kZS10ZXN0ZS0xMjM0NTY3ODkwMTIzNDU2"
})
class SchoolOpsGatewayPostgresIntegrationTest {

    private static final String SECRET = "whsec_c2VncmVkby1kZS10ZXN0ZS0xMjM0NTY3ODkwMTIzNDU2";
    private static final List<String> CALLS = new CopyOnWriteArrayList<>();
    private static final HttpServer ENGINE;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("SchoolOpsGatewayPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
        }
        try {
            ENGINE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        ENGINE.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            CALLS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " auth=" + exchange.getRequestHeaders().getFirst("Authorization")
                    + (body.isBlank() ? "" : " " + body));
            byte[] out = "{\"pautaId\":\"PT-TESTE\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        ENGINE.start();
    }

    @AfterAll
    static void stopEngine() {
        ENGINE.stop(0);
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private SchoolOpsPautaOpener opener;

    private WebTestClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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
        registry.add("schoolops.base-url", () -> "http://127.0.0.1:" + ENGINE.getAddress().getPort());
    }

    @BeforeEach
    void fixture() {
        client = WebTestClient.bindToApplicationContext(applicationContext).apply(springSecurity())
                .configureClient().responseTimeout(Duration.ofSeconds(20)).build();
        clean();
        CALLS.clear();
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) SELECT i, 'it-525-' || i, 'it-525-' || i || '@example.com', 'x', true "
                + "FROM generate_series(525001, 525003) i");
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (525001, 'IT525', 'Escola 525', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES (525001, 2193, 2194, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES (525001, 'C525', 'Informática', 525001, now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES (525001, 'A1', 12, 525001, 525001, 525001, now(), now())");
        sql("INSERT INTO subjects (id, code, name, created_at, updated_at) VALUES (525001, 'M525', 'Matemática', now(), now())");
        sql("INSERT INTO teachers (id, account_id, first_name, last_name, created_at, updated_at) VALUES (525001, 525001, 'Abner', 'Lourenço', now(), now())");
        sql("INSERT INTO teaching_assignments (teacher_id, class_id, subject_id, school_year_id, created_at) VALUES (525001, 525001, 525001, 525001, now())");
        sql("INSERT INTO users (account_id, first_name, last_name) VALUES (525002, 'Bruno', 'Neto'), (525003, 'Ana', 'Domingos')");
        sql("INSERT INTO enrollments (account_id, class_id, school_year_id, status, created_at, updated_at) VALUES "
                + "(525002, 525001, 525001, 'ACTIVE', now(), now()), (525003, 525001, 525001, 'ACTIVE', now(), now())");
        for (int i = 1; i <= 2; i++) {
            sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, created_by, visible, needs_review, source, class_id, subject_id) VALUES "
                    + "(52500" + i + ", 'P" + i + "', 'TEST', 60, 5, 525001, false, false, 'manual', 525001, 525001)");
        }
        sql("INSERT INTO exam_rooms (id, statement_id, teacher_account_id, starts_at, ends_at, duration_minutes, status, class_id) VALUES "
                + "(525001, 525001, 525001, now(), now() + interval '1 day', 60, 'CLOSED', 525001), (525002, 525002, 525001, now(), now() + interval '1 day', 60, 'CLOSED', 525001)");
        sql("INSERT INTO term_plans (id, class_id, subject_id, term_id, created_by) VALUES (525001, 525001, 525001, 1, 525001)");
        sql("INSERT INTO term_assessments (plan_id, kind, seq, title, planned_date, statement_id, room_id) VALUES "
                + "(525001, 'P1', 1, 'P1', current_date, 525001, 525001), (525001, 'P2', 1, 'P2', current_date, 525002, 525002)");
        sql("INSERT INTO term_assessments (plan_id, kind, seq, title, planned_date) VALUES (525001, 'MAC', 1, 'MAC 1', current_date)");
    }

    @AfterEach
    void clean() {
        sql("DELETE FROM term_assessments WHERE plan_id = 525001");
        sql("DELETE FROM term_plans WHERE id = 525001");
        sql("DELETE FROM exam_rooms WHERE id IN (525001, 525002)");
        sql("DELETE FROM statements WHERE id IN (525001, 525002)");
        sql("DELETE FROM enrollments WHERE class_id = 525001");
        sql("DELETE FROM users WHERE account_id IN (525002, 525003)");
        sql("DELETE FROM teaching_assignments WHERE teacher_id = 525001");
        sql("DELETE FROM teachers WHERE id = 525001");
        sql("DELETE FROM classes WHERE id = 525001");
        sql("DELETE FROM courses WHERE id = 525001");
        sql("DELETE FROM subjects WHERE id = 525001");
        sql("DELETE FROM school_years WHERE id = 525001");
        sql("DELETE FROM institutions WHERE id = 525001");
        sql("DELETE FROM accounts WHERE id IN (525001, 525002, 525003)");
    }

    @Test
    void aPlanWithAPendingMacDoesNotOpenAndAReadyOneOpensOnceWithTheContractRequest() {
        // a MAC 1 ainda está por concluir: o plano não está pronto
        assertThat(opener.getClass()).isNotNull();
        var notReady = database.sql("SELECT id FROM term_plans WHERE id = 525001 AND pauta_opened_at IS NULL").fetch().all().collectList().block(Duration.ofSeconds(10));
        assertThat(notReady).hasSize(1);
        opener.openReadyPlans();
        assertThat(CALLS).as("sem plano pronto não se chama o motor").isEmpty();

        sql("UPDATE term_assessments SET manual_done_at = now() WHERE plan_id = 525001 AND kind = 'MAC'");
        opener.openReadyPlans();

        assertThat(CALLS).hasSize(3);
        assertThat(CALLS.get(0)).startsWith("POST /v1/pautas auth=Bearer token-de-teste")
                .contains("\"pautaRef\":\"kixi-plano-525001\"")
                .contains("\"trimestre\":1").contains("\"anoLectivo\":\"2193/2194\"")
                .contains("\"sourcedId\":\"525001-525001\"")
                .contains("\"componente\":\"P1\"").contains("\"componente\":\"P2\"").contains("\"modalidade\":\"DIGITAL\"")
                .contains("\"nome\":\"Abner Lourenço\"")
                .contains("https://kixi.example/api/v1/integrations/schoolops/events");
        // os alunos vão por ordem alfabética, com número de ordem
        assertThat(CALLS.get(0).indexOf("Ana Domingos")).isLessThan(CALLS.get(0).indexOf("Bruno Neto"));
        assertThat(CALLS.get(1)).startsWith("POST /v1/pautas/PT-TESTE/provas/525001/encerramento");
        assertThat(CALLS.get(2)).startsWith("POST /v1/pautas/PT-TESTE/provas/525002/encerramento");
        assertThat(database.sql("SELECT pauta_ref FROM term_plans WHERE id = 525001").map((r, m) -> r.get(0, String.class)).one().block(Duration.ofSeconds(10)))
                .isEqualTo("PT-TESTE");

        CALLS.clear();
        opener.openReadyPlans();
        assertThat(CALLS).as("uma pauta já aberta não se abre outra vez").isEmpty();
    }

    @Test
    void eventsAreAcceptedOnlyWithAValidSignatureAndUpdateThePlan() {
        String body = "{\"specversion\":\"1.0\",\"id\":\"e1\",\"type\":\"ao.schoolops.pauta.homologada.v1\",\"subject\":\"PT-X\",\"data\":{\"pautaRef\":\"kixi-plano-525001\"}}";
        long now = System.currentTimeMillis() / 1000;
        client.post().uri("/api/v1/integrations/schoolops/events").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/integrations/schoolops/events").contentType(MediaType.APPLICATION_JSON)
                .header("webhook-id", "e1").header("webhook-timestamp", String.valueOf(now)).header("webhook-signature", "v1," + sign("e1", now, "outro corpo"))
                .bodyValue(body).exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/integrations/schoolops/events").contentType(MediaType.APPLICATION_JSON)
                .header("webhook-id", "e1").header("webhook-timestamp", String.valueOf(now - 3600)).header("webhook-signature", "v1," + sign("e1", now - 3600, body))
                .bodyValue(body).exchange().expectStatus().isUnauthorized();
        assertThat(status()).isNull();

        client.post().uri("/api/v1/integrations/schoolops/events").contentType(MediaType.APPLICATION_JSON)
                .header("webhook-id", "e1").header("webhook-timestamp", String.valueOf(now)).header("webhook-signature", "v1,xxx v1," + sign("e1", now, body))
                .bodyValue(body).exchange().expectStatus().isNoContent();
        assertThat(status()).isEqualTo("HOMOLOGADA");

        // um evento de cálculo atrasado não faz uma pauta homologada recuar
        String late = body.replace("homologada", "componente-calculado");
        client.post().uri("/api/v1/integrations/schoolops/events").contentType(MediaType.APPLICATION_JSON)
                .header("webhook-id", "e2").header("webhook-timestamp", String.valueOf(now)).header("webhook-signature", "v1," + sign("e2", now, late))
                .bodyValue(late).exchange().expectStatus().isNoContent();
        assertThat(status()).isEqualTo("HOMOLOGADA");
    }

    private String status() {
        Object value = database.sql("SELECT pauta_status FROM term_plans WHERE id = 525001").fetch().one().block(Duration.ofSeconds(10)).get("pauta_status");
        return value == null ? null : value.toString();
    }

    private static String sign(String id, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(SECRET.substring("whsec_".length())), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(10));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
