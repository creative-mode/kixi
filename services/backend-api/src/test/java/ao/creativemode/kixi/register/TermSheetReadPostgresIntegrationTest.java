package ao.creativemode.kixi.register;

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
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Quem lê as pautas do registo escolar (kixi#506), contra um PostgreSQL migrado pelo Flyway: o administrador lê tudo, o
 * professor só as pautas da turma E da disciplina que leciona, o aluno só a sua linha e só de pautas fechadas, e a conta
 * de integração não ganha leitura.
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
class TermSheetReadPostgresIntegrationTest {

    private static final long ADMIN = 506001L;
    private static final long TEACHER_A = 506002L;   // turma 506001 + disciplina 506001
    private static final long TEACHER_B = 506003L;   // turma 506002 + disciplina 506002, e turma 506001 + disciplina 506002
    private static final long ANA = 506004L;
    private static final long BRUNO = 506005L;
    private static final long ENGINE = 506006L;
    private static final String[] ACCOUNTS = {"506001", "506002", "506003", "506004", "506005", "506006"};

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private JwtService jwtService;

    private WebTestClient client;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("TermSheetReadPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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

    /** Também depois de cada teste: os anos lectivos (2093/2094) são únicos e outros testes de Postgres usam os mesmos. */
    @AfterEach
    void limpar() {
        String accounts = String.join(", ", ACCOUNTS);
        sql("DELETE FROM register_term_entries WHERE sheet_id LIKE 'PT-506-%'");
        sql("DELETE FROM register_term_sheets WHERE sheet_id LIKE 'PT-506-%'");
        sql("DELETE FROM teaching_assignments WHERE teacher_id IN (506002, 506003)");
        sql("DELETE FROM teachers WHERE id IN (506002, 506003)");
        sql("DELETE FROM classes WHERE id IN (506001, 506002)");
        sql("DELETE FROM courses WHERE id = 506001");
        sql("DELETE FROM subjects WHERE id IN (506001, 506002)");
        sql("DELETE FROM school_years WHERE id = 506001");
        sql("DELETE FROM institutions WHERE id = 506001");
        sql("DELETE FROM account_roles WHERE account_id IN (" + accounts + ")");
        sql("DELETE FROM accounts WHERE id IN (" + accounts + ")");
    }

    @BeforeEach
    void fixture() {
        client = WebTestClient.bindToApplicationContext(applicationContext).apply(springSecurity())
                .configureClient().responseTimeout(Duration.ofSeconds(20)).build();
        limpar();

        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) "
                + "SELECT i, 'it-506-' || i, 'it-506-' || i || '@example.com', 'x', true FROM generate_series(506001, 506006) i");
        role(ADMIN, "ADMIN");
        role(TEACHER_A, "TEACHER");
        role(TEACHER_B, "TEACHER");
        role(ANA, "STUDENT");
        role(BRUNO, "STUDENT");
        role(ENGINE, "INTEGRATION");
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (506001, 'IT506', 'Escola 506', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES (506001, 2093, 2094, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES (506001, 'C506', 'Informática', 506001, now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES "
                + "(506001, '12B', 12, 506001, 506001, 506001, now(), now()), (506002, '11A', 11, 506001, 506001, 506001, now(), now())");
        sql("INSERT INTO subjects (id, code, name, created_at, updated_at) VALUES (506001, 'R506', 'Redes', now(), now()), "
                + "(506002, 'S506', 'Sistemas', now(), now())");
        sql("INSERT INTO teachers (id, account_id, first_name, last_name, created_at, updated_at) VALUES "
                + "(506002, 506002, 'Helena', 'Teste', now(), now()), (506003, 506003, 'Carlos', 'Teste', now(), now())");
        sql("INSERT INTO teaching_assignments (teacher_id, class_id, subject_id, school_year_id, created_at) VALUES "
                + "(506002, 506001, 506001, 506001, now()), (506003, 506002, 506002, 506001, now()), (506003, 506001, 506002, 506001, now())");

        // A: fechada, turma 506001 / disciplina 506001 (1.º trimestre; turmaId com sufixo, como nos ensaios)
        sheet("PT-506-A", "FECHADA", "506001-506001-t99", 506001, 1);
        // B: ainda registada (não homologada), mesma turma e disciplina, 2.º trimestre
        sheet("PT-506-B", "REGISTADA", "506001-506001", 506001, 2);
        // C: fechada, outra turma e outra disciplina
        sheet("PT-506-C", "FECHADA", "506002-506002", 506002, 1);
        // D: anulada, mesma turma e disciplina de A
        sheet("PT-506-D", "ANULADA", "506001-506001", 506001, 3);
        for (String sheet : List.of("PT-506-A", "PT-506-B", "PT-506-C", "PT-506-D")) {
            entry(sheet, ANA, 1, "Ana Domingos", 15, 20, 20, 18, "POSITIVA");
            entry(sheet, BRUNO, 2, "Bruno Neto", 10, 5, 10, 8, "NEGATIVA");
        }
    }

    @Test
    void theAdministratorReadsEverySheetButAnnulledOnes() {
        List<String> ids = ids(token(ADMIN, "ADMIN"), "/api/v1/term-sheets");
        assertThat(ids).containsExactlyInAnyOrder("PT-506-A", "PT-506-B", "PT-506-C");

        bearer(token(ADMIN, "ADMIN")).get().uri("/api/v1/term-sheets/PT-506-A").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sheet.status").isEqualTo("FECHADA")
                .jsonPath("$.sheet.termNumber").isEqualTo(1)
                .jsonPath("$.sheet.classId").isEqualTo(506001)
                .jsonPath("$.sheet.className").isEqualTo("12B")
                .jsonPath("$.sheet.grade").isEqualTo(12)
                .jsonPath("$.sheet.course").isEqualTo("Informática")
                .jsonPath("$.sheet.subjectId").isEqualTo(506001)
                .jsonPath("$.sheet.subjectCode").isEqualTo("R506")
                .jsonPath("$.sheet.statistics.media").isEqualTo(13)
                .jsonPath("$.rows.length()").isEqualTo(2)
                .jsonPath("$.rows[0].name").isEqualTo("Ana Domingos")
                .jsonPath("$.rows[0].number").isEqualTo(1)
                .jsonPath("$.rows[0].p1").isEqualTo(20)
                .jsonPath("$.rows[0].p2").isEqualTo(20)
                .jsonPath("$.rows[0].mac").isEqualTo(15)
                .jsonPath("$.rows[0].mt").isEqualTo(18)
                .jsonPath("$.rows[0].status").isEqualTo("POSITIVA")
                .jsonPath("$.rows[1].status").isEqualTo("NEGATIVA");
        bearer(token(ADMIN, "ADMIN")).get().uri("/api/v1/term-sheets/PT-506-D").exchange().expectStatus().isNotFound();
        bearer(token(ADMIN, "ADMIN")).get().uri("/api/v1/term-sheets/PT-506-NAO-EXISTE").exchange().expectStatus().isNotFound();
        assertThat(ids(token(ADMIN, "ADMIN"), "/api/v1/term-sheets?termNumber=1&classId=506001")).containsExactly("PT-506-A");
    }

    @Test
    void aTeacherReadsOnlyTheSheetsOfTheClassAndSubjectTheyTeach() {
        String teacherA = token(TEACHER_A, "TEACHER");
        assertThat(ids(teacherA, "/api/v1/term-sheets")).containsExactlyInAnyOrder("PT-506-A", "PT-506-B");
        assertThat(ids(teacherA, "/api/v1/term-sheets?termNumber=1")).containsExactly("PT-506-A");
        assertThat(ids(teacherA, "/api/v1/term-sheets?subjectId=506002")).isEmpty();
        bearer(teacherA).get().uri("/api/v1/term-sheets/PT-506-A").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.rows.length()").isEqualTo(2);
        // outra turma e disciplina, uma pauta anulada e uma que não existe: a mesma resposta, sem revelar o que existe
        for (String hidden : List.of("PT-506-C", "PT-506-D", "PT-506-NAO-EXISTE")) {
            bearer(teacherA).get().uri("/api/v1/term-sheets/" + hidden).exchange().expectStatus().isForbidden();
        }

        // o professor B leciona a turma 506001, mas outra disciplina: não lê a pauta da turma 506001 / disciplina 506001
        String teacherB = token(TEACHER_B, "TEACHER");
        assertThat(ids(teacherB, "/api/v1/term-sheets")).containsExactly("PT-506-C");
        bearer(teacherB).get().uri("/api/v1/term-sheets/PT-506-A").exchange().expectStatus().isForbidden();
        bearer(teacherB).get().uri("/api/v1/term-sheets/PT-506-C").exchange().expectStatus().isOk();
    }

    @Test
    void aStudentReadsOnlyTheirOwnLineAndOnlyOfClosedSheets() {
        String ana = token(ANA, "STUDENT");
        bearer(ana).get().uri("/api/v1/me/term-sheets").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2) // A e C (fechadas); B está só registada e D anulada
                .jsonPath("$[?(@.sheetId=='PT-506-A')].row.mt").isEqualTo(18)
                .jsonPath("$[?(@.sheetId=='PT-506-A')].row.p1").isEqualTo(20)
                .jsonPath("$[?(@.sheetId=='PT-506-A')].row.status").isEqualTo("POSITIVA")
                .jsonPath("$[?(@.sheetId=='PT-506-A')].subjectCode").isEqualTo("R506");
        byte[] raw = bearer(ana).get().uri("/api/v1/me/term-sheets").exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        String json = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("Bruno").doesNotContain("PT-506-B").doesNotContain("PT-506-D")
                .doesNotContain("statistics").doesNotContain("\"rows\"");
        // a lista e o detalhe da turma são de quem leciona
        bearer(ana).get().uri("/api/v1/term-sheets").exchange().expectStatus().isForbidden();
        bearer(ana).get().uri("/api/v1/term-sheets/PT-506-A").exchange().expectStatus().isForbidden();

        bearer(token(BRUNO, "STUDENT")).get().uri("/api/v1/me/term-sheets").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[?(@.sheetId=='PT-506-A')].row.mt").isEqualTo(8);
    }

    @Test
    void integrationAnonymousAndOtherRolesAreRefused() {
        String engine = token(ENGINE, "INTEGRATION");
        bearer(engine).get().uri("/api/v1/term-sheets").exchange().expectStatus().isForbidden();
        bearer(engine).get().uri("/api/v1/term-sheets/PT-506-A").exchange().expectStatus().isForbidden();
        bearer(engine).get().uri("/api/v1/me/term-sheets").exchange().expectStatus().isForbidden();
        client.get().uri("/api/v1/term-sheets").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/me/term-sheets").exchange().expectStatus().isUnauthorized();
        // um professor não tem pautas "suas" de aluno; a escrita não existe nesta rota
        bearer(token(TEACHER_A, "TEACHER")).get().uri("/api/v1/me/term-sheets").exchange().expectStatus().isForbidden();
        bearer(token(ADMIN, "ADMIN")).post().uri("/api/v1/term-sheets").exchange().expectStatus().isForbidden();
        bearer(token(TEACHER_A, "TEACHER")).delete().uri("/api/v1/term-sheets/PT-506-A").exchange().expectStatus().isForbidden();
    }

    private List<String> ids(String token, String uri) {
        return bearer(token).get().uri(uri).exchange().expectStatus().isOk()
                .expectBodyList(java.util.Map.class).returnResult().getResponseBody().stream()
                .map(sheet -> String.valueOf(sheet.get("sheetId"))).toList();
    }

    private void sheet(String id, String status, String turmaId, long subjectId, int term) {
        sql("INSERT INTO register_term_sheets (sheet_id, context, statistics, status) VALUES ('" + id + "', CAST('{"
                + "\"turmaId\":\"" + turmaId + "\",\"disciplinaId\":\"" + subjectId + "\",\"trimestre\":" + term
                + ",\"anoLectivo\":\"2093/2094\",\"turma\":\"12B\",\"classe\":12,\"curso\":\"C506\",\"cursoNome\":\"Informática\","
                + "\"disciplina\":\"Redes\",\"disciplinaSigla\":\"R" + (subjectId == 506001 ? "506" : "x") + "\"}' AS jsonb), "
                + "CAST('{\"media\":13,\"avaliados\":2,\"inscritos\":2,\"ausentes\":0,\"positivas\":1,\"negativas\":1,\"notaMaxima\":18,\"notaMinima\":8}' AS jsonb), '"
                + status + "')");
    }

    private void entry(String sheet, long account, int number, String name, int mac, int p1, int p2, int mt, String status) {
        sql("INSERT INTO register_term_entries (sheet_id, component, account_id, score, status, payload) VALUES ('" + sheet
                + "', 'MT', " + account + ", " + mt + ", '" + status + "', CAST('{\"numero\":" + number + ",\"nome\":\"" + name
                + "\",\"mac\":" + mac + ",\"npp\":" + p1 + ",\"npt\":" + p2 + ",\"mt\":" + mt + ",\"alunoId\":\"" + account
                + "\",\"ajustada\":false,\"completa\":true}' AS jsonb))");
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
