package ao.creativemode.kixi.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import ao.creativemode.kixi.identity.service.JwtService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * As exportações da pauta (kixi#622) por papel, contra PostgreSQL e com o motor num MockWebServer (kixi#633).
 *
 * <p>A regra tem duas partes: o {@code SecurityConfig} deixa entrar ADMIN, TEACHER e COORDINATOR (o aluno não), e o
 * {@link TermPlanService#get} limita cada um ao seu plano. Os testes cobrem as duas, rota a rota, e a pauta da turma
 * (que não passa por ligação guardada) até à auditoria.
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
class TermPlanReportPostgresIntegrationTest {

    private static final long ADMIN = 633001L;
    private static final long TEACHER = 633002L;        // leciona a turma e a disciplina do plano
    private static final long OTHER_TEACHER = 633003L;  // leciona a turma, mas outra disciplina
    private static final long COORDINATOR = 633004L;
    private static final long STUDENT = 633005L;
    private static final long SECOND_STUDENT = 633006L;
    private static final long CLASS = 633001L;
    private static final long SUBJECT = 633001L;
    private static final long INSTITUTION = 633001L;
    private static final String ACCOUNTS = "633001, 633002, 633003, 633004, 633005, 633006";

    private static final MockWebServer MOTOR = new MockWebServer();
    private static final byte[] PDF = "%PDF-1.4 relatorio".getBytes(StandardCharsets.UTF_8);

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("TermPlanReportPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
        }
        try {
            MOTOR.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private JwtService jwtService;

    private WebTestClient client;
    /** O MockWebServer conta os pedidos desde o arranque; cada teste olha só para os seus. */
    private long motorAntes;

    @DynamicPropertySource
    static void postgresAndMotor(DynamicPropertyRegistry registry) {
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
        registry.add("schoolops.enabled", () -> true);
        registry.add("schoolops.base-url", () -> MOTOR.url("/").toString());
        registry.add("schoolops.token", () -> "token-do-kixi");
    }

    @AfterAll
    static void pararMotor() throws IOException {
        MOTOR.shutdown();
    }

    /** A base é partilhada pela suite: o que este teste semeia não pode aparecer nos outros. */
    @AfterEach
    void limpar() {
        clean();
    }

    @BeforeEach
    void fixture() {
        client = WebTestClient.bindToApplicationContext(applicationContext).apply(springSecurity())
                .configureClient().responseTimeout(Duration.ofSeconds(20)).build();
        MOTOR.setDispatcher(new QueueDispatcher());
        drenarMotor();
        motorAntes = MOTOR.getRequestCount();
        clean();
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) SELECT i, 'it-633-' || i, 'it-633-' || i || '@example.com', 'x', true "
                + "FROM generate_series(633001, 633006) i");
        role(ADMIN, "ADMIN");
        role(TEACHER, "TEACHER");
        role(OTHER_TEACHER, "TEACHER");
        role(COORDINATOR, "COORDINATOR");
        role(STUDENT, "STUDENT");
        role(SECOND_STUDENT, "STUDENT");
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (" + INSTITUTION + ", 'IT633', 'Escola 633', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES (633001, 3191, 3192, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES (633001, 'C633', 'Informática', " + INSTITUTION + ", now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES "
                + "(" + CLASS + ", 'A633', 12, 633001, 633001, " + INSTITUTION + ", now(), now())");
        sql("INSERT INTO subjects (id, code, name, created_at, updated_at) VALUES (633001, 'M633', 'Matemática', now(), now()), (633002, 'I633', 'Inglês', now(), now())");
        sql("INSERT INTO teachers (id, account_id, first_name, last_name, created_at, updated_at) VALUES "
                + "(633002, 633002, 'Abner', 'Teste', now(), now()), (633003, 633003, 'Andre', 'Teste', now(), now())");
        sql("INSERT INTO teaching_assignments (teacher_id, class_id, subject_id, school_year_id, created_at) VALUES "
                + "(633002, " + CLASS + ", 633001, 633001, now()), (633003, " + CLASS + ", 633002, 633001, now())");
    }

    private void clean() {
        sql("DELETE FROM report_exports");
        sql("DELETE FROM register_term_entries WHERE sheet_id LIKE 'it633-%'");
        sql("DELETE FROM register_term_sheets WHERE sheet_id LIKE 'it633-%'");
        sql("DELETE FROM term_assessments WHERE plan_id IN (SELECT id FROM term_plans WHERE class_id = " + CLASS + ")");
        sql("DELETE FROM term_plans WHERE class_id = " + CLASS);
        sql("DELETE FROM statements WHERE id = 633001");
        sql("DELETE FROM institution_coordinators WHERE institution_id = " + INSTITUTION);
        sql("DELETE FROM teaching_assignments WHERE teacher_id IN (633002, 633003)");
        sql("DELETE FROM teachers WHERE id IN (633002, 633003)");
        sql("DELETE FROM classes WHERE id = " + CLASS);
        sql("DELETE FROM courses WHERE id = 633001");
        sql("DELETE FROM subjects WHERE id IN (633001, 633002)");
        sql("DELETE FROM school_years WHERE id = 633001");
        sql("DELETE FROM institutions WHERE id = " + INSTITUTION);
        sql("DELETE FROM account_roles WHERE account_id IN (" + ACCOUNTS + ")");
        sql("DELETE FROM accounts WHERE id IN (" + ACCOUNTS + ")");
    }

    // ── Quem entra ────────────────────────────────────────────────────────────────

    @Test
    void oAlunoNaoExportaENemChegaAFalarComOMotor() {
        long plan = plano();
        String aluno = token(STUDENT, "STUDENT");
        for (String rota : rotas(plan)) {
            bearer(aluno).get().uri(rota).exchange().expectStatus().isForbidden();
        }
        assertThat(pedidosAoMotor()).as("o motor não é chamado para quem não pode exportar").isZero();
        assertThat(exportacoes()).isZero();
    }

    @Test
    void oProfessorDeOutraDisciplinaNaoExporta() {
        long plan = plano();
        bearer(token(OTHER_TEACHER, "TEACHER")).get().uri("/api/v1/term-plans/" + plan + "/reports/mini-pauta")
                .exchange().expectStatus().isForbidden();
        assertThat(pedidosAoMotor()).isZero();
        assertThat(exportacoes()).isZero();
    }

    // ── Quem exporta ──────────────────────────────────────────────────────────────

    @Test
    void oProfessorDonoExportaAMiniPautaEOmotorRecebeOPedido() throws Exception {
        long plan = plano();
        pautaNoMotor(plan);
        ligacao();
        pdf();

        bearer(token(TEACHER, "TEACHER")).get().uri("/api/v1/term-plans/" + plan + "/reports/mini-pauta").exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_PDF)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"mini-pauta-" + plan + ".pdf\"")
                .expectBody().consumeWith(r -> assertThat(r.getResponseBody()).isEqualTo(PDF));

        RecordedRequest pedido = MOTOR.takeRequest();
        assertThat(pedido.getPath()).isEqualTo("/v1/relatorios");
        assertThat(pedido.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token-do-kixi");
        assertThat(pedido.getBody().readUtf8()).contains("\"pautaId\":\"PT-633\"").contains("\"tipo\":\"MINI_PAUTA\"");
        assertThat(MOTOR.takeRequest().getPath()).isEqualTo("/relatorios/pt-633.pdf");
        assertThat(exportacoes("MINI_PAUTA")).isEqualTo(1);
        assertThat(auditoriaDoPlano(plan)).isEqualTo(TEACHER);
    }

    @Test
    void oCoordenadorDaInstituicaoEoAdministradorTambemExportam() throws Exception {
        long plan = plano();
        pautaNoMotor(plan);
        coordenadorDaInstituicao();

        ligacao();
        pdf();
        bearer(token(COORDINATOR, "COORDINATOR")).get().uri("/api/v1/term-plans/" + plan + "/reports/mini-pauta").exchange()
                .expectStatus().isOk().expectHeader().contentType(MediaType.APPLICATION_PDF);

        ligacao();
        pdf();
        bearer(token(ADMIN, "ADMIN")).get().uri("/api/v1/term-plans/" + plan + "/reports/mini-pauta").exchange()
                .expectStatus().isOk().expectHeader().contentType(MediaType.APPLICATION_PDF);

        assertThat(exportacoes("MINI_PAUTA")).isEqualTo(2);
        // cada um fica registado por si: o coordenador não exporta «em nome» do professor.
        assertThat(exportacoesDo(COORDINATOR)).isEqualTo(1);
        assertThat(exportacoesDo(ADMIN)).isEqualTo(1);
    }

    @Test
    void oResultadoDaProvaLevaOEnunciadoEAMarcaAAvaliacaoNaAuditoria() throws Exception {
        long plan = plano();
        pautaNoMotor(plan);
        sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, created_by, visible, needs_review, source, class_id, subject_id) VALUES "
                + "(633001, 'P1 Matemática', 'TEST', 60, 5, " + TEACHER + ", false, false, 'manual', " + CLASS + ", " + SUBJECT + ")");
        long avaliacao = avaliacao(plan, "P1");
        sql("UPDATE term_assessments SET statement_id = 633001 WHERE id = " + avaliacao);
        ligacao();
        pdf();

        bearer(token(TEACHER, "TEACHER")).get()
                .uri("/api/v1/term-plans/" + plan + "/assessments/" + avaliacao + "/reports/result").exchange()
                .expectStatus().isOk().expectHeader().contentType(MediaType.APPLICATION_PDF);

        assertThat(MOTOR.takeRequest().getBody().readUtf8()).contains("\"provaId\":\"633001\"").contains("\"tipo\":\"RESULTADO_PROVA\"");
        assertThat(exportacoes("RESULTADO_PROVA")).isEqualTo(1);
        assertThat(exportacoesComAvaliacao(avaliacao)).isEqualTo(1);
    }

    // ── A pauta da turma ─────────────────────────────────────────────────────────

    @Test
    void aPautaDaTurmaVaiNumPedidoSoComOsAlunosENotas() throws Exception {
        long plan = plano();
        folhaDaTurma();
        pdf();

        bearer(token(TEACHER, "TEACHER")).get().uri("/api/v1/term-plans/" + plan + "/reports/class").exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_PDF)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"pauta-turma-" + plan + ".pdf\"")
                .expectBody().consumeWith(r -> assertThat(r.getResponseBody()).isEqualTo(PDF));

        // Um só pedido, e é o da pauta da turma: não há ligação assinada pelo meio.
        assertThat(pedidosAoMotor()).isEqualTo(1);
        RecordedRequest pedido = MOTOR.takeRequest();
        assertThat(pedido.getPath()).isEqualTo("/v1/pautas-turma");
        assertThat(pedido.getBody().readUtf8())
                .contains("\"contratoVersao\":\"1\"")
                .contains("\"turma\"")
                .contains("\"numero\":1")
                .contains("\"componente\":\"P1\"")
                .contains("\"nota\":14.5");
        assertThat(exportacoes("PAUTA_TURMA")).isEqualTo(1);
    }

    @Test
    void semFolhasRegistadasAPautaDaTurmaDiz409ESemChamarOMotor() {
        long plan = plano();

        bearer(token(TEACHER, "TEACHER")).get().uri("/api/v1/term-plans/" + plan + "/reports/class").exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").value(d -> assertThat(String.valueOf(d)).contains("pautas"));

        assertThat(pedidosAoMotor()).isZero();
        assertThat(exportacoes()).isZero();
    }

    @Test
    void semAlunosComNumeroAPautaDaTurmaDiz409ESemChamarOMotor() {
        long plan = plano();
        sql("INSERT INTO register_term_sheets (sheet_id, context, status) VALUES ('it633-sem-numero', '"
                + folhaContexto() + "'::jsonb, 'FECHADA')");
        sql("INSERT INTO register_term_entries (sheet_id, component, account_id, score, status, payload) VALUES "
                + "('it633-sem-numero', 'NPP', " + STUDENT + ", 12, 'FULLY_GRADED', '{\"nome\":\"Sem número\"}'::jsonb)");

        bearer(token(TEACHER, "TEACHER")).get().uri("/api/v1/term-plans/" + plan + "/reports/class").exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").value(d -> assertThat(String.valueOf(d)).contains("alunos"));

        assertThat(pedidosAoMotor()).isZero();
        assertThat(exportacoes()).isZero();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────

    private List<String> rotas(long plan) {
        return List.of(
                "/api/v1/term-plans/" + plan + "/reports/mini-pauta",
                "/api/v1/term-plans/" + plan + "/assessments/" + avaliacao(plan, "P1") + "/reports/result",
                "/api/v1/term-plans/" + plan + "/reports/class");
    }

    /** Um plano do professor dono, com as datas já passadas para não depender do relógio. */
    private long plano() {
        LocalDate d = LocalDate.now();
        String corpo = "{\"classId\":" + CLASS + ",\"subjectId\":" + SUBJECT + ",\"termId\":1,"
                + "\"p1Date\":\"" + d.plusDays(10) + "\",\"p2Date\":\"" + d.plusDays(40) + "\",\"macCount\":0,\"macDates\":[]}";
        AtomicLong id = new AtomicLong();
        bearer(token(TEACHER, "TEACHER")).post().uri("/api/v1/term-plans").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(corpo).exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.id").value(v -> id.set(((Number) v).longValue()));
        return id.get();
    }

    private void pautaNoMotor(long plan) {
        sql("UPDATE term_plans SET pauta_ref = 'PT-633', pauta_opened_at = now() WHERE id = " + plan);
    }

    private void coordenadorDaInstituicao() {
        sql("INSERT INTO institution_coordinators (institution_id, account_id) VALUES (" + INSTITUTION + ", " + COORDINATOR + ")");
    }

    private long avaliacao(long plan, String kind) {
        return count("SELECT id FROM term_assessments WHERE plan_id = " + plan + " AND kind = '" + kind + "' AND seq = 1");
    }

    /** Uma folha do registo da turma, no formato que o motor escreve: contexto da turma/trimestre e linhas por aluno. */
    private void folhaDaTurma() {
        sql("INSERT INTO register_term_sheets (sheet_id, context, status) VALUES ('it633-folha', '" + folhaContexto() + "'::jsonb, 'FECHADA')");
        sql("INSERT INTO register_term_entries (sheet_id, component, account_id, score, status, payload) VALUES "
                + "('it633-folha', 'NPP', " + STUDENT + ", 14.5, 'FULLY_GRADED', '{\"numero\":1,\"nome\":\"Aluno Um\"}'::jsonb), "
                + "('it633-folha', 'NPT', " + STUDENT + ", 10, 'FULLY_GRADED', '{\"numero\":1,\"nome\":\"Aluno Um\"}'::jsonb), "
                + "('it633-folha', 'NPP', " + SECOND_STUDENT + ", 12, 'FULLY_GRADED', '{\"numero\":2,\"nome\":\"Aluno Dois\"}'::jsonb)");
    }

    private String folhaContexto() {
        return "{\"turmaId\":\"" + CLASS + "-A\",\"trimestre\":1,\"disciplinaId\":633001,"
                + "\"disciplinaSigla\":\"M633\",\"disciplina\":\"Matemática\",\"anoLectivo\":\"2025-2026\"}";
    }

    /** O motor responde à ligação e depois ao PDF; a URL assinada é deste mesmo servidor, como o validador exige. */
    private void ligacao() {
        MOTOR.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"url\":\"" + MOTOR.url("/relatorios/pt-633.pdf") + "\"}"));
    }

    private void pdf() {
        MOTOR.enqueue(new MockResponse().setHeader("Content-Type", "application/pdf").setBody(new String(PDF, StandardCharsets.UTF_8)));
    }

    // ── SQL e JWTs ──────────────────────────────────────────────────────────────

    /** Cada teste só olha para os pedidos que faz: um teste anterior pode ter deixado respostas por consumir. */
    private void drenarMotor() {
        try {
            while (MOTOR.takeRequest(5, TimeUnit.MILLISECONDS) != null) {
                // descarta
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long pedidosAoMotor() {
        return MOTOR.getRequestCount() - motorAntes;
    }

    private long exportacoes() {
        return count("SELECT count(*) FROM report_exports");
    }

    private long exportacoes(String kind) {
        return count("SELECT count(*) FROM report_exports WHERE kind = '" + kind + "'");
    }

    private long exportacoesDo(long account) {
        return count("SELECT count(*) FROM report_exports WHERE account_id = " + account);
    }

    private long exportacoesComAvaliacao(long avaliacao) {
        return count("SELECT count(*) FROM report_exports WHERE assessment_id = " + avaliacao);
    }

    private long auditoriaDoPlano(long plan) {
        return count("SELECT account_id FROM report_exports WHERE plan_id = " + plan + " ORDER BY id LIMIT 1");
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
