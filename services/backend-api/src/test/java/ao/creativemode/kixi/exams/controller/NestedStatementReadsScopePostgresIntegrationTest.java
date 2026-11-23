package ao.creativemode.kixi.exams.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import ao.creativemode.kixi.identity.service.JwtService;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Pré-promoção DEV→QUA (vaga 1A, P0-1): as questões e as opções de um enunciado (com o gabarito {@code isCorrect}) só
 * as lê quem pode ler o enunciado. Duas instituições: o professor da escola A é recusado (403) em todas as leituras
 * aninhadas do enunciado da escola B, incluindo a lixeira, como já é em {@code GET /statements/{id}}; o professor de B
 * e o administrador leem; o aluno lê o enunciado publicado sem gabarito e não vê a lixeira. As escritas continuam
 * recusadas ao professor de fora.
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
class NestedStatementReadsScopePostgresIntegrationTest {

    private static final long TEACHER_A = 740001L;
    private static final long TEACHER_B = 740002L;
    private static final long ADMIN = 740003L;
    private static final long STUDENT = 740004L;
    private static final String ACCOUNTS = "740001, 740002, 740003, 740004";
    private static final long STATEMENT_A = 740001L;
    private static final long STATEMENT_B = 740002L;
    private static final long QUESTION_B = 740002L;
    private static final long QUESTION_B_DELETED = 740003L;
    private static final long RIGHT_OPTION_B = 740002L;
    private static final long OPTION_B_DELETED = 740003L;

    private static final String QUESTIONS_OF_B = "/api/v1/statements/" + STATEMENT_B + "/questions";
    private static final String OPTIONS_OF_B = QUESTIONS_OF_B + "/" + QUESTION_B + "/options";

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
                    "NestedStatementReadsScopePostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) SELECT i, 'it-740-' || i, 'it-740-' || i || '@example.com', 'x', true "
                + "FROM generate_series(740001, 740004) i");
        role(TEACHER_A, "TEACHER");
        role(TEACHER_B, "TEACHER");
        role(ADMIN, "ADMIN");
        role(STUDENT, "STUDENT");
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES "
                + "(740001, 'IT740A', 'Escola 740A', now(), now()), (740002, 'IT740B', 'Escola 740B', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES (740001, 2291, 2292, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES "
                + "(740001, 'C740A', 'Informática', 740001, now(), now()), (740002, 'C740B', 'Informática', 740002, now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES "
                + "(740001, 'A1', 12, 740001, 740001, 740001, now(), now()), (740002, 'B1', 12, 740002, 740001, 740002, now(), now())");
        sql("INSERT INTO subjects (id, code, name, created_at, updated_at) VALUES (740001, 'M740', 'Matemática', now(), now())");
        sql("INSERT INTO teachers (id, account_id, first_name, last_name, created_at, updated_at) VALUES "
                + "(740001, 740001, 'Prof', 'A', now(), now()), (740002, 740002, 'Prof', 'B', now(), now())");
        sql("INSERT INTO teaching_assignments (teacher_id, class_id, subject_id, school_year_id, created_at) VALUES "
                + "(740001, 740001, 740001, 740001, now()), (740002, 740002, 740001, 740001, now())");
        sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, created_by, visible, needs_review, source, "
                + "institution_id, class_id, subject_id) VALUES "
                + "(" + STATEMENT_A + ", 'Prova da escola A', 'TEST', 60, 5, " + TEACHER_A + ", true, false, 'manual', 740001, 740001, 740001),"
                + "(" + STATEMENT_B + ", 'Prova da escola B', 'TEST', 60, 5, " + TEACHER_B + ", true, false, 'manual', 740002, 740002, 740001)");
        sql("INSERT INTO questions (id, statement_id, number, text, question_type, max_score, order_index, deleted_at) VALUES "
                + "(740001, " + STATEMENT_A + ", 1, 'Questao de A', 'multiple_choice', 5, 1, NULL),"
                + "(" + QUESTION_B + ", " + STATEMENT_B + ", 1, 'Questao de B', 'multiple_choice', 5, 1, NULL),"
                + "(" + QUESTION_B_DELETED + ", " + STATEMENT_B + ", 2, 'Questao apagada de B', 'multiple_choice', 5, 2, now())");
        sql("INSERT INTO question_options (id, question_id, option_label, option_text, is_correct, order_index, deleted_at) VALUES "
                + "(740001, 740001, 'A', '3', false, 1, NULL), (740005, 740001, 'B', '4', true, 2, NULL),"
                + "(740004, " + QUESTION_B + ", 'A', '3', false, 1, NULL),"
                + "(" + RIGHT_OPTION_B + ", " + QUESTION_B + ", 'B', '4', true, 2, NULL),"
                + "(" + OPTION_B_DELETED + ", " + QUESTION_B + ", 'C', '5', false, 3, now())");
    }

    @AfterEach
    void clean() {
        sql("DELETE FROM question_options WHERE question_id IN (740001, 740002, 740003)");
        sql("DELETE FROM questions WHERE statement_id IN (740001, 740002)");
        sql("DELETE FROM statements WHERE id IN (740001, 740002)");
        sql("DELETE FROM teaching_assignments WHERE teacher_id IN (740001, 740002)");
        sql("DELETE FROM teachers WHERE id IN (740001, 740002)");
        sql("DELETE FROM classes WHERE id IN (740001, 740002)");
        sql("DELETE FROM courses WHERE id IN (740001, 740002)");
        sql("DELETE FROM subjects WHERE id = 740001");
        sql("DELETE FROM school_years WHERE id = 740001");
        sql("DELETE FROM institutions WHERE id IN (740001, 740002)");
        sql("DELETE FROM account_roles WHERE account_id IN (" + ACCOUNTS + ")");
        sql("DELETE FROM accounts WHERE id IN (" + ACCOUNTS + ")");
    }

    /** As seis leituras aninhadas (a lixeira incluída) do enunciado da escola B. */
    static Stream<String> nestedReadsOfB() {
        return Stream.of(
                QUESTIONS_OF_B,
                QUESTIONS_OF_B + "/" + QUESTION_B,
                QUESTIONS_OF_B + "/trash",
                OPTIONS_OF_B,
                OPTIONS_OF_B + "/" + RIGHT_OPTION_B,
                OPTIONS_OF_B + "/trash");
    }

    @ParameterizedTest(name = "professor de A em GET {0}")
    @MethodSource("nestedReadsOfB")
    void aTeacherOfAnotherSchoolIsForbidden(String path) {
        bearer(token(TEACHER_A, "TEACHER")).get().uri(path).exchange()
                .expectStatus().isForbidden()
                .expectBody(String.class).value(body ->
                        assertThat(body).doesNotContain("Questao de B").doesNotContain("isCorrect"));
    }

    @ParameterizedTest(name = "professor de B em GET {0}")
    @MethodSource("nestedReadsOfB")
    void theTeacherOfTheSchoolReads(String path) {
        bearer(token(TEACHER_B, "TEACHER")).get().uri(path).exchange().expectStatus().isOk();
    }

    @ParameterizedTest(name = "ADMIN em GET {0}")
    @MethodSource("nestedReadsOfB")
    void anAdministratorReads(String path) {
        bearer(token(ADMIN, "ADMIN")).get().uri(path).exchange().expectStatus().isOk();
    }

    @Test
    void theTeacherOfTheSchoolStillGetsTheAnswerKeyAndTheTrash() {
        WebTestClient teacher = bearer(token(TEACHER_B, "TEACHER"));
        teacher.get().uri(OPTIONS_OF_B).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[?(@.isCorrect == true)].optionLabel").isEqualTo("B");
        teacher.get().uri(QUESTIONS_OF_B + "/trash").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1);
        teacher.get().uri(OPTIONS_OF_B + "/trash").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1);
    }

    @Test
    void theTeacherOfAReadsTheirOwnStatementAndNotTheOtherWayRound() {
        bearer(token(TEACHER_A, "TEACHER")).get().uri("/api/v1/statements/" + STATEMENT_A + "/questions")
                .exchange().expectStatus().isOk();
        bearer(token(TEACHER_B, "TEACHER")).get().uri("/api/v1/statements/" + STATEMENT_A + "/questions")
                .exchange().expectStatus().isForbidden();
        bearer(token(TEACHER_B, "TEACHER")).get().uri("/api/v1/statements/" + STATEMENT_A + "/questions/740001/options")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void aStudentReadsThePublishedStatementWithoutTheAnswerKeyAndNeverTheTrash() {
        WebTestClient student = bearer(token(STUDENT, "STUDENT"));
        student.get().uri(OPTIONS_OF_B).exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[?(@.isCorrect == true)]").isEmpty();
        student.get().uri(OPTIONS_OF_B + "/" + RIGHT_OPTION_B).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.isCorrect").doesNotExist();
        student.get().uri(QUESTIONS_OF_B).exchange().expectStatus().isOk();
        student.get().uri(QUESTIONS_OF_B + "/trash").exchange().expectStatus().isNotFound();
        student.get().uri(OPTIONS_OF_B + "/trash").exchange().expectStatus().isNotFound();
    }

    /** Verbo, caminho e corpo das escritas aninhadas que o professor da escola A tenta no enunciado da escola B. */
    static Stream<Arguments> nestedWritesOfB() {
        String question = "{\"text\":\"adulterada\",\"maxScore\":5}";
        String option = "{\"optionLabel\":\"Z\",\"optionText\":\"adulterada\",\"isCorrect\":true}";
        return Stream.of(
                Arguments.of(HttpMethod.POST, QUESTIONS_OF_B, question),
                Arguments.of(HttpMethod.PUT, QUESTIONS_OF_B + "/" + QUESTION_B, question),
                Arguments.of(HttpMethod.DELETE, QUESTIONS_OF_B + "/" + QUESTION_B, null),
                Arguments.of(HttpMethod.POST, QUESTIONS_OF_B + "/" + QUESTION_B_DELETED + "/restore", null),
                Arguments.of(HttpMethod.DELETE, QUESTIONS_OF_B + "/" + QUESTION_B + "/purge", null),
                Arguments.of(HttpMethod.PUT, QUESTIONS_OF_B + "/" + QUESTION_B + "/correct-option",
                        "{\"optionId\":740004}"),
                Arguments.of(HttpMethod.POST, OPTIONS_OF_B, option),
                Arguments.of(HttpMethod.PUT, OPTIONS_OF_B + "/" + RIGHT_OPTION_B, option),
                Arguments.of(HttpMethod.DELETE, OPTIONS_OF_B + "/" + RIGHT_OPTION_B, null),
                Arguments.of(HttpMethod.POST, OPTIONS_OF_B + "/" + OPTION_B_DELETED + "/restore", null),
                Arguments.of(HttpMethod.DELETE, OPTIONS_OF_B + "/" + RIGHT_OPTION_B + "/purge", null));
    }

    @ParameterizedTest(name = "professor de A em {0} {1}")
    @MethodSource("nestedWritesOfB")
    void theWritesOfAForeignTeacherStayRefused(HttpMethod method, String path, String body) {
        WebTestClient.RequestBodySpec request = bearer(token(TEACHER_A, "TEACHER")).method(method).uri(path)
                .contentType(MediaType.APPLICATION_JSON);
        var spec = body == null ? request.exchange() : request.bodyValue(body).exchange();
        spec.expectStatus().isForbidden();

        assertThat(count("SELECT COUNT(*) FROM questions WHERE statement_id = " + STATEMENT_B
                + " AND deleted_at IS NULL AND text = 'Questao de B'")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM question_options WHERE question_id = " + QUESTION_B
                + " AND deleted_at IS NULL")).isEqualTo(2L);
        assertThat(count("SELECT COUNT(*) FROM question_options WHERE id = " + RIGHT_OPTION_B + " AND is_correct")).isEqualTo(1L);
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
