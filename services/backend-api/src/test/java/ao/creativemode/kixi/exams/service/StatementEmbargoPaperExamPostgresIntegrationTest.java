package ao.creativemode.kixi.exams.service;

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
 * S8 com casos reais do plano: as perguntas de um enunciado numa prova por terminar não se abrem ao aluno; a P1/P2 em papel
 * (sem sala nem {@code manual_done_at}) só termina quando a pauta do plano passa a HOMOLOGADA, RECTIFICADA ou ANULADA (N1);
 * a avaliação dispensada liberta; se o enunciado entra em duas provas basta uma por terminar; e o coordenador da instituição
 * do enunciado lê sempre, o de outra instituição não (N2).
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
class StatementEmbargoPaperExamPostgresIntegrationTest {

    private static final long TEACHER = 668001L;
    private static final long STUDENT = 668002L;
    private static final long COORDINATOR = 668003L;
    private static final long OTHER_COORDINATOR = 668004L;
    private static final long INSTITUTION = 668001L;
    private static final long OTHER_INSTITUTION = 668002L;
    private static final long CLASS = 668001L;
    private static final long STATEMENT = 668001L;
    private static final long SUBJECT_A = 668001L;
    private static final long SUBJECT_B = 668002L;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private JwtService jwtService;

    private WebTestClient client;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("StatementEmbargoPaperExamPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) SELECT i, 'it-668-' || i, 'it-668-' || i || '@example.com', 'x', true "
                + "FROM generate_series(668001, 668004) i");
        role(TEACHER, "TEACHER");
        role(STUDENT, "STUDENT");
        role(COORDINATOR, "COORDINATOR");
        role(OTHER_COORDINATOR, "COORDINATOR");
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (" + INSTITUTION + ", 'IT668', 'Escola 668', now(), now()), ("
                + OTHER_INSTITUTION + ", 'IT669', 'Escola 669', now(), now())");
        sql("INSERT INTO institution_coordinators (institution_id, account_id) VALUES (" + INSTITUTION + ", " + COORDINATOR + "), ("
                + OTHER_INSTITUTION + ", " + OTHER_COORDINATOR + ")");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES (668001, 3291, 3292, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES (668001, 'C668', 'Informática', " + INSTITUTION + ", now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES "
                + "(" + CLASS + ", 'A668', 12, 668001, 668001, " + INSTITUTION + ", now(), now())");
        sql("INSERT INTO subjects (id, code, name, created_at, updated_at) VALUES (" + SUBJECT_A + ", 'M668', 'Matemática', now(), now()), ("
                + SUBJECT_B + ", 'I668', 'Inglês', now(), now())");
        sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, created_by, visible, needs_review, source, institution_id) VALUES ("
                + STATEMENT + ", 'Prova it-668', 'TEST', 60, 5, " + TEACHER + ", true, false, 'manual', " + INSTITUTION + ")");
        sql("INSERT INTO questions (id, statement_id, number, text, question_type, max_score, order_index) VALUES (668001, " + STATEMENT
                + ", 1, 'Quanto é 2+2?', 'open', 5, 1)");
    }

    @AfterEach
    void clean() {
        sql("DELETE FROM term_plans WHERE class_id = " + CLASS);
        sql("DELETE FROM questions WHERE statement_id = " + STATEMENT);
        sql("DELETE FROM statements WHERE id = " + STATEMENT);
        sql("DELETE FROM institution_coordinators WHERE account_id IN (" + COORDINATOR + ", " + OTHER_COORDINATOR + ")");
        sql("DELETE FROM classes WHERE id = " + CLASS);
        sql("DELETE FROM courses WHERE id = 668001");
        sql("DELETE FROM school_years WHERE id = 668001");
        sql("DELETE FROM subjects WHERE id IN (" + SUBJECT_A + ", " + SUBJECT_B + ")");
        sql("DELETE FROM institutions WHERE id IN (" + INSTITUTION + ", " + OTHER_INSTITUTION + ")");
        sql("DELETE FROM account_roles WHERE account_id BETWEEN 668001 AND 668004");
        sql("DELETE FROM accounts WHERE id BETWEEN 668001 AND 668004");
    }

    @Test
    void aStatementInAPaperExamThatHasNotFinishedIsClosedToTheStudent() {
        long plan = plan(SUBJECT_A);
        assessment(plan, "P1");

        refused(STUDENT, "STUDENT", "/full");
        refused(STUDENT, "STUDENT", "/questions");
        // O catálogo continua legível: só as perguntas ficam retidas.
        ok(STUDENT, "STUDENT", "");
        // Pauta aberta mas EM_CURSO: a prova de papel ainda não terminou.
        sql("UPDATE term_plans SET pauta_status = 'EM_CURSO' WHERE id = " + plan);
        refused(STUDENT, "STUDENT", "/full");
    }

    @Test
    void theStudentReadsItOnceThePautaOfThePlanIsFinal() {
        long plan = plan(SUBJECT_A);
        assessment(plan, "P1");
        refused(STUDENT, "STUDENT", "/full");

        for (String status : List.of("HOMOLOGADA", "RECTIFICADA", "ANULADA")) {
            sql("UPDATE term_plans SET pauta_status = '" + status + "' WHERE id = " + plan);
            ok(STUDENT, "STUDENT", "/full");
            ok(STUDENT, "STUDENT", "/questions");
        }
    }

    @Test
    void oneUnfinishedExamIsEnoughToHoldTheStatement() {
        long finished = plan(SUBJECT_A);
        assessment(finished, "P1");
        sql("UPDATE term_plans SET pauta_status = 'HOMOLOGADA' WHERE id = " + finished);
        ok(STUDENT, "STUDENT", "/full");

        long open = plan(SUBJECT_B);
        assessment(open, "P2");
        refused(STUDENT, "STUDENT", "/full");
        refused(STUDENT, "STUDENT", "/questions");
    }

    @Test
    void aDispensedAssessmentReleasesTheStatement() {
        long plan = plan(SUBJECT_A);
        assessment(plan, "P1");
        refused(STUDENT, "STUDENT", "/full");
        sql("UPDATE term_assessments SET dispensed_at = now(), dispensed_by = " + TEACHER + ", dispensed_reason = 'it' WHERE plan_id = " + plan);
        ok(STUDENT, "STUDENT", "/full");
    }

    @Test
    void theCoordinatorOfTheStatementInstitutionReadsItWhileAnotherInstitutionAndTheStudentDoNot() {
        long plan = plan(SUBJECT_A);
        assessment(plan, "P1");

        ok(COORDINATOR, "COORDINATOR", "/full");
        ok(COORDINATOR, "COORDINATOR", "/questions");
        refused(OTHER_COORDINATOR, "COORDINATOR", "/full");
        refused(OTHER_COORDINATOR, "COORDINATOR", "/questions");
        refused(STUDENT, "STUDENT", "/full");
    }

    private long plan(long subject) {
        sql("INSERT INTO term_plans (class_id, subject_id, term_id, created_by) VALUES (" + CLASS + ", " + subject + ", 1, " + TEACHER + ")");
        return count("SELECT id FROM term_plans WHERE class_id = " + CLASS + " AND subject_id = " + subject);
    }

    private void assessment(long plan, String kind) {
        sql("INSERT INTO term_assessments (plan_id, kind, seq, title, planned_date, statement_id) VALUES (" + plan + ", '" + kind + "', 1, '" + kind
                + "', CURRENT_DATE, " + STATEMENT + ")");
    }

    private void refused(long account, String role, String suffix) {
        bearer(token(account, role)).get().uri("/api/v1/statements/" + STATEMENT + suffix).exchange().expectStatus().isForbidden();
    }

    private void ok(long account, String role, String suffix) {
        bearer(token(account, role)).get().uri("/api/v1/statements/" + STATEMENT + suffix).exchange().expectStatus().isOk();
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
