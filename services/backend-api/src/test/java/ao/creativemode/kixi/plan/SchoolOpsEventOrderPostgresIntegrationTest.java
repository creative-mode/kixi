package ao.creativemode.kixi.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * Vaga 2, V2-13: os eventos do motor chegam fora de ordem (reenvios, atrasos) e o estado da pauta do plano não recua. Estados
 * monótonos EM_CURSO → HOMOLOGADA → RECTIFICADA; ANULADA é terminal; entre eventos do mesmo estado vale o {@code time} do
 * CloudEvent (um mais antigo não sobrescreve um mais recente). Um evento que faria o estado recuar responde 204 e não altera nada.
 */
@SpringBootTest(properties = {
        "spring.r2dbc.pool.max-size=2",
        "spring.r2dbc.pool.initial-size=0",
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test",
        "schoolops.poll-ms=86400000",
        "schoolops.events-secret=whsec_c2VncmVkby1kZS10ZXN0ZS0xMjM0NTY3ODkwMTIzNDU2"
})
class SchoolOpsEventOrderPostgresIntegrationTest {

    private static final String SECRET = "whsec_c2VncmVkby1kZS10ZXN0ZS0xMjM0NTY3ODkwMTIzNDU2";
    private static final long PLAN = 646001L;
    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private DatabaseClient database;

    private WebTestClient client;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("SchoolOpsEventOrderPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) VALUES (646001, 'it-646', 'it-646@example.com', 'x', true)");
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (646001, 'IT646', 'Escola 646', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES (646001, 2591, 2592, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES (646001, 'C646', 'Informática', 646001, now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES "
                + "(646001, 'A1', 12, 646001, 646001, 646001, now(), now())");
        sql("INSERT INTO subjects (id, code, name, created_at, updated_at) VALUES (646001, 'M646', 'Matemática', now(), now())");
        sql("INSERT INTO term_plans (id, class_id, subject_id, term_id, created_by) VALUES (" + PLAN + ", 646001, 646001, 1, 646001)");
    }

    @AfterEach
    void clean() {
        sql("DELETE FROM term_plans WHERE id = " + PLAN);
        sql("DELETE FROM classes WHERE id = 646001");
        sql("DELETE FROM courses WHERE id = 646001");
        sql("DELETE FROM subjects WHERE id = 646001");
        sql("DELETE FROM school_years WHERE id = 646001");
        sql("DELETE FROM institutions WHERE id = 646001");
        sql("DELETE FROM accounts WHERE id = 646001");
    }

    @Test
    void theNormalSequenceStillAdvancesTheState() {
        send("componente-calculado", "n1", T0);
        assertThat(status()).isEqualTo("EM_CURSO");
        send("homologada", "n2", T0.plusSeconds(60));
        assertThat(status()).isEqualTo("HOMOLOGADA");
        send("rectificada", "n3", T0.plusSeconds(120));
        assertThat(status()).isEqualTo("RECTIFICADA");
    }

    @Test
    void aPlanAnnulledBeforeTheHomologationStaysAnnulled() {
        send("componente-calculado", "a1", T0);
        send("anulada", "a2", T0.plusSeconds(60));
        assertThat(status()).isEqualTo("ANULADA");
    }

    /** Permutação 1 do relatório: {@code rectificada} seguida de uma {@code homologada} atrasada. */
    @Test
    void aLateHomologationDoesNotRevertARectifiedPauta() {
        send("homologada", "r1", T0);
        send("rectificada", "r2", T0.plusSeconds(120));
        send("homologada", "r1-atrasada", T0.plusSeconds(60));
        assertThat(status()).isEqualTo("RECTIFICADA");
    }

    /** Permutação 2: {@code anulada} seguida de uma {@code homologada} atrasada. */
    @Test
    void aLateHomologationDoesNotReviveAnAnnulledPauta() {
        send("componente-calculado", "x1", T0);
        send("anulada", "x2", T0.plusSeconds(120));
        send("homologada", "x3-atrasada", T0.plusSeconds(60));
        assertThat(status()).isEqualTo("ANULADA");
    }

    /** Permutação 3: {@code anulada} seguida de um {@code componente-calculado} atrasado. */
    @Test
    void aLateCalculationDoesNotReopenAnAnnulledPauta() {
        send("anulada", "y1", T0.plusSeconds(120));
        send("componente-calculado", "y2-atrasado", T0);
        assertThat(status()).isEqualTo("ANULADA");
        send("rectificada", "y3-atrasada", T0.plusSeconds(10));
        assertThat(status()).isEqualTo("ANULADA");
    }

    @Test
    void anAnnulmentAfterTheHomologationIsIgnoredBecauseTheEngineRefusesIt() {
        // o motor recusa anular uma pauta homologada (409): um evento assim é inconsistente e não derruba o estado
        send("homologada", "h1", T0);
        send("anulada", "h2", T0.plusSeconds(60));
        assertThat(status()).isEqualTo("HOMOLOGADA");
    }

    @Test
    void anOlderEventOfTheSameStateDoesNotOverwriteANewerOneButANewerOneDoes() {
        send("homologada", "t1", T0.plusSeconds(100));
        assertThat(eventTime()).isEqualTo(T0.plusSeconds(100));
        send("homologada", "t2-antiga", T0);
        assertThat(eventTime()).as("a homologação mais antiga não sobrescreve a mais recente").isEqualTo(T0.plusSeconds(100));
        send("homologada", "t3-nova", T0.plusSeconds(200));
        assertThat(eventTime()).isEqualTo(T0.plusSeconds(200));
        send("rectificada", "t4", T0.plusSeconds(300));
        send("rectificada", "t5-antiga", T0.plusSeconds(250));
        assertThat(eventTime()).isEqualTo(T0.plusSeconds(300));
    }

    @Test
    void resendingTheSameEventIsHarmless() {
        send("homologada", "d1", T0);
        send("homologada", "d1", T0);
        send("homologada", "d1", T0);
        assertThat(status()).isEqualTo("HOMOLOGADA");
        assertThat(eventTime()).isEqualTo(T0);
    }

    @Test
    void anEventWithoutATimeStillAppliesWhenTheStateAdvances() {
        sendRaw("homologada", "s1", null);
        assertThat(status()).isEqualTo("HOMOLOGADA");
    }

    private void send(String type, String id, Instant time) {
        sendRaw(type, id, time);
    }

    private void sendRaw(String type, String id, Instant time) {
        String body = "{\"specversion\":\"1.0\",\"id\":\"" + id + "\",\"type\":\"ao.schoolops.pauta." + type + ".v1\""
                + (time == null ? "" : ",\"time\":\"" + time + "\"") + ",\"subject\":\"PT-X\",\"data\":{\"pautaRef\":\"kixi-plano-" + PLAN + "\"}}";
        long now = System.currentTimeMillis() / 1000;
        client.post().uri("/api/v1/integrations/schoolops/events").contentType(MediaType.APPLICATION_JSON)
                .header("webhook-id", id + "-" + SEQ.incrementAndGet()).header("webhook-timestamp", String.valueOf(now))
                .header("webhook-signature", "v1," + sign(id + "-" + SEQ.get(), now, body))
                .bodyValue(body).exchange().expectStatus().isNoContent();
    }

    private String status() {
        Object value = database.sql("SELECT pauta_status FROM term_plans WHERE id = " + PLAN).fetch().one().block(Duration.ofSeconds(10)).get("pauta_status");
        return value == null ? null : value.toString();
    }

    private Instant eventTime() {
        Object value = database.sql("SELECT pauta_event_time FROM term_plans WHERE id = " + PLAN).fetch().one().block(Duration.ofSeconds(10)).get("pauta_event_time");
        return value == null ? null : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
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
