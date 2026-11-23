package ao.creativemode.kixi.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import ao.creativemode.kixi.identity.service.JwtService;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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
 * Pré-promoção DEV→QUA (vaga 1A, P1-2): uma entrada com o carácter NUL (U+0000), que o PostgreSQL não guarda, ou com um
 * texto maior do que a coluna, era um 500; agora é um 400 no formato de erro padrão (RFC 9457), sem expor a mensagem da
 * base de dados. Uma rota por caso do relatório: {@code PUT /me}, a pesquisa de enunciados, o título de uma avaliação do
 * plano, a dispensa com motivo enorme e a rectificação com {@code decisao} com NUL.
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
class InvalidInputPostgresIntegrationTest {

    private static final long STUDENT = 741001L;
    private static final long TEACHER = 741002L;
    private static final long COORDINATOR = 741003L;
    private static final long ENGINE = 741004L;
    private static final String ACCOUNTS = "741001, 741002, 741003, 741004";
    private static final long INSTITUTION = 741001L;
    private static final long CLASS = 741001L;
    private static final long SUBJECT = 741001L;
    private static final String NUL = "\\u0000";

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private DatabaseClient database;
    @Autowired
    private JwtService jwtService;

    private WebTestClient client;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException(
                    "InvalidInputPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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
                .configureClient().responseTimeout(Duration.ofSeconds(30)).build();
        clean();
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) SELECT i, 'it-741-' || i, 'it-741-' || i || '@example.com', 'x', true "
                + "FROM generate_series(741001, 741004) i");
        role(STUDENT, "STUDENT");
        role(TEACHER, "TEACHER");
        role(COORDINATOR, "COORDINATOR");
        role(ENGINE, "INTEGRATION");
        sql("INSERT INTO users (account_id, first_name, last_name) VALUES "
                + "(" + STUDENT + ", 'Aluno', 'Teste')");
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (" + INSTITUTION + ", 'IT741', 'Escola 741', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES (741001, 2391, 2392, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES (741001, 'C741', 'Informática', " + INSTITUTION + ", now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES "
                + "(" + CLASS + ", 'A1', 12, 741001, 741001, " + INSTITUTION + ", now(), now())");
        sql("INSERT INTO subjects (id, code, name, created_at, updated_at) VALUES (" + SUBJECT + ", 'M741', 'Matemática', now(), now())");
        sql("INSERT INTO teachers (id, account_id, first_name, last_name, created_at, updated_at) VALUES (741002, " + TEACHER + ", 'Prof', 'Teste', now(), now())");
        sql("INSERT INTO teaching_assignments (teacher_id, class_id, subject_id, school_year_id, created_at) VALUES (741002, " + CLASS + ", " + SUBJECT + ", 741001, now())");
        sql("INSERT INTO institution_coordinators (institution_id, account_id) VALUES (" + INSTITUTION + ", " + COORDINATOR + ")");
    }

    @AfterEach
    void clean() {
        sql("DELETE FROM register_term_entries WHERE sheet_id = 'it741-sheet'");
        sql("DELETE FROM register_term_sheets WHERE sheet_id = 'it741-sheet'");
        sql("DELETE FROM term_assessments WHERE plan_id IN (SELECT id FROM term_plans WHERE class_id = " + CLASS + ")");
        sql("DELETE FROM term_plans WHERE class_id = " + CLASS);
        sql("DELETE FROM institution_coordinators WHERE institution_id = " + INSTITUTION);
        sql("DELETE FROM teaching_assignments WHERE teacher_id = 741002");
        sql("DELETE FROM teachers WHERE id = 741002");
        sql("DELETE FROM classes WHERE id = " + CLASS);
        sql("DELETE FROM courses WHERE id = 741001");
        sql("DELETE FROM subjects WHERE id = " + SUBJECT);
        sql("DELETE FROM school_years WHERE id = 741001");
        sql("DELETE FROM institutions WHERE id = " + INSTITUTION);
        sql("DELETE FROM users WHERE account_id IN (" + ACCOUNTS + ")");
        sql("DELETE FROM account_roles WHERE account_id IN (" + ACCOUNTS + ")");
        sql("DELETE FROM accounts WHERE id IN (" + ACCOUNTS + ")");
    }

    @Test
    void putMeWithNulInTheFirstOrLastNameIs400() {
        WebTestClient student = bearer(token(STUDENT, "STUDENT"));
        refused(student.put().uri("/api/v1/me").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"first_name\":\"" + NUL + "\",\"last_name\":\"x\"}"));
        refused(student.put().uri("/api/v1/me").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"first_name\":\"x\",\"last_name\":\"a" + NUL + "b\"}"));
        // e o nome continua o que era
        assertThat(text("SELECT first_name FROM users WHERE account_id = " + STUDENT)).isEqualTo("Aluno");
    }

    @Test
    void theStatementSearchWithNulIs400() {
        refused(bearer(token(STUDENT, "STUDENT")).get().uri(java.net.URI.create("/api/v1/statements/search?query=%00")));
        refused(bearer(token(TEACHER, "TEACHER")).get().uri(java.net.URI.create("/api/v1/statements/search?query=a%00b")));
    }

    @Test
    void anAssessmentTitleWithNulOrTooLongIs400() {
        String teacher = token(TEACHER, "TEACHER");
        long plan = plan(teacher, 1);
        long p1 = assessment(plan, "P1");
        String uri = "/api/v1/term-plans/" + plan + "/assessments/" + p1;
        refused(patch(teacher, uri, "{\"title\":\"" + NUL + "\"}"));
        refused(patch(teacher, uri, "{\"title\":\"" + "x".repeat(100_000) + "\"}"));
        refused(patch(teacher, uri, "{\"title\":\"" + "x".repeat(201) + "\"}"));
        // um título que cabe continua a gravar-se
        patch(teacher, uri, "{\"title\":\"Teste de Maio\"}").expectStatus().isOk();
    }

    @Test
    void aDispenseReasonThatIsTooLongIs400() {
        String teacher = token(TEACHER, "TEACHER");
        long plan = plan(teacher, 1);
        long mac = assessment(plan, "MAC");
        String uri = "/api/v1/term-plans/" + plan + "/assessments/" + mac + "/dispense";
        String coordinator = token(COORDINATOR, "COORDINATOR");
        refused(bearer(coordinator).post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"" + "x".repeat(100_000) + "\"}"));
        refused(bearer(coordinator).post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"a" + NUL + "b\"}"));
        assertThat(count("SELECT COUNT(*) FROM term_assessments WHERE id = " + mac + " AND dispensed_at IS NOT NULL")).isZero();
        bearer(coordinator).post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"Sem aulas suficientes\"}").exchange().expectStatus().isOk();
    }

    @Test
    void anAppealDecisionWithNulIs400() {
        refused(bearer(token(ENGINE, "INTEGRATION")).post()
                .uri("/api/v1/school-register/exams/741001/appeals/it741-a/rectify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"alunoId\":" + STUDENT + ",\"pontosActuais\":1,\"pontosFinais\":2,\"decisao\":\"" + NUL + "\"}"));
    }

    /** Vaga 1C, P1-1: uma pauta só com {@code classId} fazia rebentar o ecrã do gestor; o registo recusa-a. */
    @Test
    void aTermSheetWithoutItsContextIs400AndACompleteOneIsAccepted() {
        WebTestClient engine = bearer(token(ENGINE, "INTEGRATION"));
        String uri = "/api/v1/school-register/term-sheets/it741-sheet";
        refused(engine.put().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue("{\"classId\":1}"));
        refused(engine.put().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"contexto\":{\"turmaId\":\"1-1\",\"trimestre\":1},\"entradas\":[]}"));
        assertThat(count("SELECT COUNT(*) FROM register_term_sheets WHERE sheet_id = 'it741-sheet'")).isZero();
        engine.put().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"contexto\":{\"turmaId\":\"1-1\",\"turma\":\"A1\",\"disciplinaId\":\"1\",\"disciplina\":\"Matemática\","
                        + "\"trimestre\":1,\"anoLectivo\":\"2025/2026\"},\"estatisticas\":{},\"entradas\":[]}")
                .exchange().expectStatus().isNoContent();
        assertThat(count("SELECT COUNT(*) FROM register_term_sheets WHERE sheet_id = 'it741-sheet'")).isEqualTo(1L);
    }

    @Test
    void theErrorIsTheStandardProblemDetailWithoutTheDatabaseMessage() {
        bearer(token(STUDENT, "STUDENT")).get().uri(java.net.URI.create("/api/v1/statements/search?query=%00")).exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.title").isEqualTo("Bad Request")
                .jsonPath("$.detail").value(detail -> assertThat(String.valueOf(detail))
                        .doesNotContainIgnoringCase("byte sequence")
                        .doesNotContainIgnoringCase("postgres")
                        .doesNotContainIgnoringCase("0x00"));
    }

    private void refused(WebTestClient.RequestHeadersSpec<?> request) {
        request.exchange().expectStatus().isBadRequest();
    }

    private void refused(WebTestClient.ResponseSpec response) {
        response.expectStatus().isBadRequest();
    }

    private WebTestClient.ResponseSpec patch(String token, String uri, String body) {
        return bearer(token).patch().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private long plan(String teacher, int macCount) {
        LocalDate d = LocalDate.now();
        StringBuilder dates = new StringBuilder();
        for (int i = 0; i < macCount; i++) dates.append(i == 0 ? "" : ",").append('"').append(d.plusDays(10 + i)).append('"');
        String body = "{\"classId\":" + CLASS + ",\"subjectId\":" + SUBJECT + ",\"termId\":1,\"p1Date\":\"" + d.plusDays(1) + "\",\"p2Date\":\""
                + d.plusDays(30) + "\",\"macCount\":" + macCount + ",\"macDates\":[" + dates + "]}";
        AtomicLong id = new AtomicLong();
        bearer(teacher).post().uri("/api/v1/term-plans").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                .expectStatus().isCreated().expectBody().jsonPath("$.id").value(v -> id.set(((Number) v).longValue()));
        return id.get();
    }

    private long assessment(long plan, String kind) {
        return count("SELECT id FROM term_assessments WHERE plan_id = " + plan + " AND kind = '" + kind + "' AND seq = 1");
    }

    private long count(String query) {
        Long value = database.sql(query).map((row, meta) -> row.get(0, Long.class)).one().block(Duration.ofSeconds(10));
        return value == null ? 0 : value;
    }

    private String text(String query) {
        return database.sql(query).map((row, meta) -> row.get(0, String.class)).one().block(Duration.ofSeconds(10));
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
