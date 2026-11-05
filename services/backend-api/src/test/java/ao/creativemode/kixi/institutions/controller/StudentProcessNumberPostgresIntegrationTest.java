package ao.creativemode.kixi.institutions.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import ao.creativemode.kixi.identity.service.JwtService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
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
 * O número de processo do aluno na escola (kixi#512) contra um Postgres migrado (V75): só o administrador o escreve,
 * é único por escola e não entre escolas, um número em branco apaga-o, a validação recusa o que não se imprime numa
 * pauta e o índice único segura a base mesmo sem o serviço.
 *
 * <p>Ids a partir de 512001, à parte dos blocos dos outros testes de Postgres.</p>
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=reactive",
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
class StudentProcessNumberPostgresIntegrationTest {

    private static final long ADMIN = 512001L;
    private static final long TEACHER = 512002L;
    private static final long ANA = 512003L;
    private static final long BRUNO = 512004L;
    private static final long SCHOOL_A = 512001L;
    private static final long SCHOOL_B = 512002L;
    private static final String ACCOUNTS = "512001, 512002, 512003, 512004";

    @Autowired private ApplicationContext applicationContext;
    @Autowired private DatabaseClient database;
    @Autowired private JwtService jwtService;
    private WebTestClient client;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("StudentProcessNumberPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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

    @AfterEach
    void limpar() {
        sql("DELETE FROM institution_students WHERE institution_id IN (512001, 512002)");
        sql("DELETE FROM users WHERE account_id IN (" + ACCOUNTS + ")");
        sql("DELETE FROM institutions WHERE id IN (512001, 512002)");
        sql("DELETE FROM account_roles WHERE account_id IN (" + ACCOUNTS + ")");
        sql("DELETE FROM accounts WHERE id IN (" + ACCOUNTS + ")");
    }

    @BeforeEach
    void fixture() {
        client = WebTestClient.bindToApplicationContext(applicationContext).apply(springSecurity())
                .configureClient().responseTimeout(Duration.ofSeconds(20)).build();
        limpar();
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) "
                + "SELECT i, 'it-512-' || i, 'it-512-' || i || '@example.com', 'x', true FROM generate_series(512001, 512004) i");
        role(ADMIN, "ADMIN");
        role(TEACHER, "TEACHER");
        role(ANA, "STUDENT");
        role(BRUNO, "STUDENT");
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES "
                + "(512001, 'IT512A', 'Escola A', now(), now()), (512002, 'IT512B', 'Escola B', now(), now())");
        sql("INSERT INTO users (id, account_id, first_name, last_name) VALUES (512003, 512003, 'Ana', 'Domingos'), (512004, 512004, 'Bruno', 'Neto')");
    }

    @Test
    void theAdministratorSetsTheNumberAndTheLinkIsCreatedWhenTheStudentHadNone() {
        put(admin(), SCHOOL_A, 512003, "P-2026/0042").expectStatus().isOk()
                .expectBody().jsonPath("$.userId").isEqualTo(512003).jsonPath("$.processNumber").isEqualTo("P-2026/0042");

        bearer(admin()).get().uri("/api/v1/institutions/" + SCHOOL_A + "/students").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].firstName").isEqualTo("Ana").jsonPath("$[0].processNumber").isEqualTo("P-2026/0042");
    }

    @Test
    void theNumberIsUniqueInASchoolButTheSameNumberLivesInAnother() {
        put(admin(), SCHOOL_A, 512003, "100").expectStatus().isOk();
        put(admin(), SCHOOL_A, 512004, "100").expectStatus().isEqualTo(409);
        // outra escola, o mesmo número: é outro aluno noutro sítio
        put(admin(), SCHOOL_B, 512004, "100").expectStatus().isOk();
        // o próprio aluno a repetir o seu número não é conflito
        put(admin(), SCHOOL_A, 512003, "100").expectStatus().isOk();
    }

    @Test
    void aBlankNumberClearsItAndFreesItForAnotherStudent() {
        put(admin(), SCHOOL_A, 512003, "100").expectStatus().isOk();
        put(admin(), SCHOOL_A, 512003, "   ").expectStatus().isOk()
                .expectBody().jsonPath("$.processNumber").doesNotExist();
        put(admin(), SCHOOL_A, 512004, "100").expectStatus().isOk();
        assertThat(count("SELECT count(*) FROM institution_students WHERE institution_id = 512001 AND process_number IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void theNumberIsTrimmedAndWhatCannotBePrintedOnASheetIsRefused() {
        put(admin(), SCHOOL_A, 512003, "  A-1  ").expectStatus().isOk().expectBody().jsonPath("$.processNumber").isEqualTo("A-1");
        for (String bad : List.of("x".repeat(31), "<script>", "linha\nnova", "-começa", "a;b")) {
            put(admin(), SCHOOL_A, 512004, bad).expectStatus().isBadRequest();
        }
        assertThat(count("SELECT count(*) FROM institution_students WHERE user_id = 512004")).as("nada se gravou").isZero();
    }

    @Test
    void onlyTheAdministratorWritesItNeitherTheTeacherNorTheStudentThemselves() {
        put(token(TEACHER, "TEACHER"), SCHOOL_A, 512003, "1").expectStatus().isForbidden();
        put(token(ANA, "STUDENT"), SCHOOL_A, 512003, "1").expectStatus().isForbidden();
        client.put().uri("/api/v1/institutions/" + SCHOOL_A + "/students/512003/process-number")
                .bodyValue(Map.of("processNumber", "1")).exchange().expectStatus().isUnauthorized();
        assertThat(count("SELECT count(*) FROM institution_students WHERE process_number IS NOT NULL AND institution_id IN (512001, 512002)")).isZero();
    }

    @Test
    void anUnknownSchoolOrStudentIsNotFound() {
        put(admin(), 512999L, 512003, "1").expectStatus().isNotFound();
        put(admin(), SCHOOL_A, 512999L, "1").expectStatus().isNotFound();
    }

    @Test
    void aRemovedLinkFreesTheNumberAndRestoringItAfterAnotherTookItBringsItBackWithoutThatNumber() {
        put(admin(), SCHOOL_A, 512003, "100").expectStatus().isOk();
        bearer(admin()).delete().uri("/api/v1/institutions/" + SCHOOL_A + "/students/512003").exchange().expectStatus().isNoContent();
        put(admin(), SCHOOL_A, 512004, "100").expectStatus().isOk();

        bearer(admin()).post().uri("/api/v1/institutions/" + SCHOOL_A + "/students/512003").exchange().expectStatus().isNoContent();

        assertThat(count("SELECT count(*) FROM institution_students WHERE user_id = 512003 AND deleted_at IS NULL AND process_number IS NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM institution_students WHERE user_id = 512004 AND process_number = '100'")).isEqualTo(1);
    }

    @Test
    void theDatabaseHoldsTheRulesEvenWithoutTheService() {
        sql("INSERT INTO institution_students (institution_id, user_id, process_number) VALUES (512001, 512003, '7')");
        assertThatThrownBy(() -> sql("INSERT INTO institution_students (institution_id, user_id, process_number) VALUES (512001, 512004, '7')"))
                .as("único por escola").hasMessageContaining("uq_institution_students_process_number");
        assertThatThrownBy(() -> sql("UPDATE institution_students SET process_number = '   ' WHERE user_id = 512003"))
                .as("em branco não é número").hasMessageContaining("chk_institution_students_process_number");
        assertThatThrownBy(() -> sql("UPDATE institution_students SET process_number = '" + "x".repeat(31) + "' WHERE user_id = 512003"))
                .as("até 30 caracteres").hasMessageContaining("process_number");
    }

    private WebTestClient.ResponseSpec put(String token, long school, long user, String number) {
        return bearer(token).put().uri("/api/v1/institutions/" + school + "/students/" + user + "/process-number")
                .bodyValue(Map.of("processNumber", number)).exchange();
    }

    private String admin() {
        return token(ADMIN, "ADMIN");
    }

    private long count(String query) {
        return database.sql(query).map((row, meta) -> row.get(0, Long.class)).one().block(Duration.ofSeconds(10));
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
