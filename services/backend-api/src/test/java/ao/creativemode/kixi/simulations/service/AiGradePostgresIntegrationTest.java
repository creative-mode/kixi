package ao.creativemode.kixi.simulations.service;

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

/**
 * A correção pela IA das provas de um plano do trimestre (kixi#542): o motor guarda a nota, a observação e a confiança com
 * {@code PUT /simulation-answers/{id}/ai-grade} (só ADMIN e INTEGRATION, idempotente, nunca por cima de uma correção de pessoa),
 * uma reclamação deferida rectifica a nota da IA, e o professor deixa de corrigir à mão as respostas das salas de um plano.
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
class AiGradePostgresIntegrationTest {

    private static final long ADMIN = 543001L;
    private static final long TEACHER = 543002L;
    private static final long STUDENT = 543003L;
    private static final long ENGINE = 543004L;
    private static final String ACCOUNTS = "543001, 543002, 543003, 543004";
    private static final String BODY = "{\"scoreObtained\":4.0,\"feedback\":\"Falta o tempo de vida da variável.\",\"level\":\"PARCIAL\",\"confidence\":0.82}";

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private JwtService jwtService;

    private WebTestClient client;
    private long answer;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("AiGradePostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) SELECT i, 'it-543-' || i, 'it-543-' || i || '@example.com', 'x', true "
                + "FROM generate_series(543001, 543004) i");
        role(ADMIN, "ADMIN");
        role(TEACHER, "TEACHER");
        role(STUDENT, "STUDENT");
        role(ENGINE, "INTEGRATION");
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (543001, 'IT543', 'Escola 543', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES (543001, 2195, 2196, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES (543001, 'C543', 'Informática', 543001, now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES (543001, 'A1', 12, 543001, 543001, 543001, now(), now())");
        sql("INSERT INTO subjects (id, code, name, created_at, updated_at) VALUES (543001, 'M543', 'Matemática', now(), now())");
        sql("INSERT INTO teachers (id, account_id, first_name, last_name, created_at, updated_at) VALUES (543002, 543002, 'Abner', 'Teste', now(), now())");
        sql("INSERT INTO teaching_assignments (teacher_id, class_id, subject_id, school_year_id, created_at) VALUES (543002, 543001, 543001, 543001, now())");
        sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, created_by, visible, needs_review, source, class_id, subject_id) VALUES "
                + "(543001, 'P1 #543', 'TEST', 60, 6, " + TEACHER + ", false, false, 'manual', 543001, 543001)");
        sql("INSERT INTO questions (id, statement_id, number, text, question_type, max_score, order_index) VALUES "
                + "(543001, 543001, 1, 'Explique a diferença entre local e global.', 'open', 6, 1)");
        sql("INSERT INTO exam_rooms (id, statement_id, teacher_account_id, starts_at, ends_at, duration_minutes, status, class_id) VALUES "
                + "(543001, 543001, " + TEACHER + ", now(), now() + interval '1 day', 60, 'CLOSED', 543001)");
        sql("INSERT INTO simulations (id, account_id, statement_id, status, exam_room_id, final_score) VALUES (543001, " + STUDENT + ", 543001, 'FINISHED', 543001, 0)");
        sql("INSERT INTO exam_room_participants (exam_room_id, account_id, simulation_id, joined_at) VALUES (543001, " + STUDENT + ", 543001, CURRENT_TIMESTAMP)");
        sql("INSERT INTO simulation_answers (simulation_id, question_id, answer_text, review_status) VALUES (543001, 543001, 'A local vive na função.', 'PENDING_REVIEW')");
        answer = scalar("SELECT id FROM simulation_answers WHERE simulation_id = 543001");
    }

    @AfterEach
    void clean() {
        sql("DELETE FROM term_assessments WHERE plan_id = 543001");
        sql("DELETE FROM term_plans WHERE id = 543001");
        sql("DELETE FROM exam_room_participants WHERE exam_room_id = 543001");
        sql("DELETE FROM simulation_answers WHERE simulation_id = 543001");
        sql("DELETE FROM simulations WHERE id = 543001");
        sql("DELETE FROM exam_rooms WHERE id = 543001");
        sql("DELETE FROM questions WHERE id = 543001");
        sql("DELETE FROM statements WHERE id = 543001");
        sql("DELETE FROM teaching_assignments WHERE teacher_id = 543002");
        sql("DELETE FROM teachers WHERE id = 543002");
        sql("DELETE FROM classes WHERE id = 543001");
        sql("DELETE FROM courses WHERE id = 543001");
        sql("DELETE FROM subjects WHERE id = 543001");
        sql("DELETE FROM school_years WHERE id = 543001");
        sql("DELETE FROM institutions WHERE id = 543001");
        sql("DELETE FROM account_roles WHERE account_id IN (" + ACCOUNTS + ")");
        sql("DELETE FROM accounts WHERE id IN (" + ACCOUNTS + ")");
    }

    @Test
    void theEngineStoresWhatTheAiSaidAndRepeatingItChangesNothing() {
        aiGrade(token(ENGINE, "INTEGRATION"), BODY).expectStatus().isOk().expectBody()
                .jsonPath("$.scoreObtained").isEqualTo(4.0).jsonPath("$.reviewStatus").isEqualTo("AI_GRADED")
                .jsonPath("$.aiFeedback").isEqualTo("Falta o tempo de vida da variável.")
                .jsonPath("$.aiLevel").isEqualTo("PARCIAL").jsonPath("$.aiConfidence").isEqualTo(0.82);
        assertThat(text("SELECT review_status FROM simulation_answers WHERE id = " + answer)).isEqualTo("AI_GRADED");
        assertThat(text("SELECT ai_level FROM simulation_answers WHERE id = " + answer)).isEqualTo("PARCIAL");
        assertThat(scalar("SELECT (final_score)::bigint FROM simulations WHERE id = 543001")).isEqualTo(4L);
        String stamp = text("SELECT ai_graded_at::text FROM simulation_answers WHERE id = " + answer);

        // reentrega do mesmo job: nada muda (nem a hora da correção)
        aiGrade(token(ADMIN, "ADMIN"), BODY).expectStatus().isOk();
        assertThat(text("SELECT ai_graded_at::text FROM simulation_answers WHERE id = " + answer)).isEqualTo(stamp);

        // a IA pode corrigir de novo (outra passagem) e a nota final acompanha
        aiGrade(token(ENGINE, "INTEGRATION"), "{\"scoreObtained\":5.0,\"feedback\":\"Melhor.\",\"level\":\"TOTAL\",\"confidence\":0.9}")
                .expectStatus().isOk().expectBody().jsonPath("$.scoreObtained").isEqualTo(5.0);
        assertThat(scalar("SELECT (final_score)::bigint FROM simulations WHERE id = 543001")).isEqualTo(5L);
    }

    @Test
    void onlyTheEngineAndTheAdministratorWriteTheAiGradeAndItIsValidated() {
        aiGrade(token(TEACHER, "TEACHER"), BODY).expectStatus().isForbidden();
        aiGrade(token(STUDENT, "STUDENT"), BODY).expectStatus().isForbidden();
        client.put().uri("/api/v1/simulation-answers/" + answer + "/ai-grade").contentType(MediaType.APPLICATION_JSON).bodyValue(BODY)
                .exchange().expectStatus().isUnauthorized();
        String engine = token(ENGINE, "INTEGRATION");
        aiGrade(engine, "{\"scoreObtained\":7.0,\"feedback\":\"x\"}").expectStatus().isBadRequest();
        aiGrade(engine, "{\"scoreObtained\":-1,\"feedback\":\"x\"}").expectStatus().isBadRequest();
        aiGrade(engine, "{\"scoreObtained\":3,\"feedback\":\"x\",\"level\":\"OUTRO\"}").expectStatus().isBadRequest();
        aiGrade(engine, "{\"scoreObtained\":3,\"feedback\":\"x\",\"confidence\":1.5}").expectStatus().isBadRequest();
        bearer(engine).put().uri("/api/v1/simulation-answers/999999999/ai-grade").contentType(MediaType.APPLICATION_JSON).bodyValue(BODY)
                .exchange().expectStatus().isNotFound();
        assertThat(text("SELECT review_status FROM simulation_answers WHERE id = " + answer)).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void theAiNeverReplacesAPersonAndAnAppealRectifiesTheAiGrade() {
        String engine = token(ENGINE, "INTEGRATION");
        aiGrade(engine, BODY).expectStatus().isOk();

        // a reclamação deferida rectifica a nota da IA: passa a ser de uma pessoa e a IA já não a substitui
        bearer(engine).patch().uri("/api/v1/simulation-answers/" + answer + "/grade?rectify=true").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"scoreObtained\":5.0}").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.scoreObtained").isEqualTo(5.0).jsonPath("$.reviewStatus").isEqualTo("GRADED");
        aiGrade(engine, BODY).expectStatus().isEqualTo(409);
        assertThat(scalar("SELECT (score_obtained)::bigint FROM simulation_answers WHERE id = " + answer)).isEqualTo(5L);

        // uma objetiva já corrigida automaticamente não passa pela IA
        sql("UPDATE simulation_answers SET review_status = 'AUTO_GRADED' WHERE id = " + answer);
        aiGrade(engine, BODY).expectStatus().isEqualTo(409);
    }

    @Test
    void theTeacherNoLongerGradesByHandTheRoomsOfAPlanButStillDoesInFreeRooms() {
        String teacher = token(TEACHER, "TEACHER");
        String grade = "/api/v1/simulation-answers/" + answer + "/grade";
        // sala livre (fora de qualquer plano): continua como antes
        bearer(teacher).patch().uri(grade).contentType(MediaType.APPLICATION_JSON).bodyValue("{\"scoreObtained\":3.0}")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.reviewStatus").isEqualTo("GRADED");
        sql("UPDATE simulation_answers SET review_status = 'PENDING_REVIEW', score_obtained = NULL WHERE id = " + answer);

        // a mesma sala ligada a um plano do trimestre: a correção é da IA
        sql("INSERT INTO term_plans (id, class_id, subject_id, term_id, created_by) VALUES (543001, 543001, 543001, 1, " + TEACHER + ")");
        sql("INSERT INTO term_assessments (plan_id, kind, seq, title, planned_date, statement_id, room_id) VALUES "
                + "(543001, 'P1', 1, 'P1', current_date, 543001, 543001)");
        bearer(teacher).patch().uri(grade).contentType(MediaType.APPLICATION_JSON).bodyValue("{\"scoreObtained\":3.0}")
                .exchange().expectStatus().isEqualTo(409);
        // a correção em massa da sala também
        bearer(teacher).post().uri("/api/v1/exam-rooms/543001/grade").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"grades\":[{\"answerId\":" + answer + ",\"score\":3.0}],\"byKey\":false}")
                .exchange().expectStatus().isEqualTo(409);
        assertThat(text("SELECT review_status FROM simulation_answers WHERE id = " + answer)).isEqualTo("PENDING_REVIEW");
        // o administrador (que também opera o motor do servidor, ainda com uma conta ADMIN) não é travado: sem isto, publicar o
        // Kixi antes do motor novo deixava a correção das pautas em curso em incidente
        bearer(token(ADMIN, "ADMIN")).patch().uri(grade).contentType(MediaType.APPLICATION_JSON).bodyValue("{\"scoreObtained\":2.0}")
                .exchange().expectStatus().isOk();
        sql("UPDATE simulation_answers SET review_status = 'PENDING_REVIEW', score_obtained = NULL WHERE id = " + answer);

        // a IA corrige e o professor vê o que ela disse, só para leitura
        aiGrade(token(ENGINE, "INTEGRATION"), BODY).expectStatus().isOk();
        bearer(teacher).get().uri("/api/v1/exam-rooms/543001/review").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.planLinked").isEqualTo(true).jsonPath("$.aiGraded").isEqualTo(1).jsonPath("$.pending").isEqualTo(0)
                .jsonPath("$.questions[0].answers[0].status").isEqualTo("AI_GRADED")
                .jsonPath("$.questions[0].answers[0].aiFeedback").isEqualTo("Falta o tempo de vida da variável.")
                .jsonPath("$.questions[0].answers[0].aiLevel").isEqualTo("PARCIAL")
                .jsonPath("$.questions[0].answers[0].scoreObtained").isEqualTo(4.0);
        // e o aluno vê a observação da IA no resultado, porque a sala está fechada
        bearer(token(STUDENT, "STUDENT")).get().uri("/api/v1/simulations/543001/result").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.questions[0].aiFeedback").isEqualTo("Falta o tempo de vida da variável.")
                .jsonPath("$.questions[0].reviewStatus").isEqualTo("AI_GRADED");
    }

    private WebTestClient.ResponseSpec aiGrade(String token, String body) {
        return bearer(token).put().uri("/api/v1/simulation-answers/" + answer + "/ai-grade").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange();
    }

    private String token(long account, String role) {
        return jwtService.generateToken(account, List.of(role));
    }

    private WebTestClient bearer(String token) {
        return client.mutate().defaultHeaders(headers -> headers.setBearerAuth(token)).build();
    }

    private void role(long account, String role) {
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + account + ", id FROM roles WHERE name = '" + role + "'");
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(10));
    }

    private long scalar(String query) {
        return ((Number) database.sql(query).map((row, meta) -> row.get(0)).one().block(Duration.ofSeconds(10))).longValue();
    }

    private String text(String query) {
        return database.sql(query).map((row, meta) -> String.valueOf(row.get(0))).one().block(Duration.ofSeconds(10));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
