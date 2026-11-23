package ao.creativemode.kixi.simulations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import ao.creativemode.kixi.identity.service.JwtService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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
 * Re-correção de uma resposta já corrigida (kixi#509): o motor SchoolOps rectifica a pontuação de uma dissertativa quando uma
 * reclamação é deferida. {@code PATCH .../grade?rectify=true} só aceita respostas já corrigidas, a corrigir pela primeira vez
 * continua a só aceitar as pendentes, e a nota final da simulação é recalculada.
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
class AnswerRectificationPostgresIntegrationTest {

    private static final long TEACHER = 508001L;
    private static final long STUDENT = 508002L;
    private static final long ENGINE = 508003L;
    private static final long ADMIN = 508004L;
    private static final long STATEMENT = 508001L;
    private static final long QUESTION = 508001L;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private JwtService jwtService;

    private WebTestClient client;
    private String teacherToken;
    private String studentToken;
    private String engineToken;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("AnswerRectificationPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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
        sql("DELETE FROM simulation_answers WHERE simulation_id IN (SELECT id FROM simulations WHERE statement_id = " + STATEMENT + ")");
        sql("DELETE FROM simulations WHERE statement_id = " + STATEMENT);
        sql("DELETE FROM question_options WHERE question_id IN (" + QUESTION + ", " + (QUESTION + 1) + ")");
        sql("DELETE FROM questions WHERE id IN (" + QUESTION + ", " + (QUESTION + 1) + ")");
        sql("DELETE FROM statements WHERE id = " + STATEMENT);
        sql("DELETE FROM teaching_assignments WHERE teacher_id = 508001");
        sql("DELETE FROM teachers WHERE id = 508001");
        sql("DELETE FROM classes WHERE id = 508001");
        sql("DELETE FROM courses WHERE id = 508001");
        sql("DELETE FROM subjects WHERE id = 508001");
        sql("DELETE FROM school_years WHERE id = 508001");
        sql("DELETE FROM institutions WHERE id = 508001");
        sql("DELETE FROM account_roles WHERE account_id IN (" + TEACHER + ", " + STUDENT + ", " + ENGINE + ", " + ADMIN + ")");
        sql("DELETE FROM accounts WHERE id IN (" + TEACHER + ", " + STUDENT + ", " + ENGINE + ", " + ADMIN + ")");
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) SELECT i, 'it-508-' || i, 'it-508-' || i || '@example.com', 'x', true "
                + "FROM generate_series(508001, 508004) i");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + TEACHER + ", id FROM roles WHERE name = 'TEACHER'");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + STUDENT + ", id FROM roles WHERE name = 'STUDENT'");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + ENGINE + ", id FROM roles WHERE name = 'INTEGRATION'");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + ADMIN + ", id FROM roles WHERE name = 'ADMIN'");
        // o professor leciona a turma e a disciplina do enunciado: autorizado a corrigir, para o reteste F2 distinguir o 409 do 403
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (508001, 'IT508', 'Escola 508', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES (508001, 2491, 2492, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES (508001, 'C508', 'Informática', 508001, now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES "
                + "(508001, 'A1', 12, 508001, 508001, 508001, now(), now())");
        sql("INSERT INTO subjects (id, code, name, created_at, updated_at) VALUES (508001, 'M508', 'Matemática', now(), now())");
        sql("INSERT INTO teachers (id, account_id, first_name, last_name, created_at, updated_at) VALUES (508001, " + TEACHER + ", 'Prof', 'Teste', now(), now())");
        sql("INSERT INTO teaching_assignments (teacher_id, class_id, subject_id, school_year_id, created_at) VALUES (508001, 508001, 508001, 508001, now())");
        sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, created_by, visible, needs_review, source, class_id, subject_id) VALUES ("
                + STATEMENT + ", 'Prova de re-correcao #508', 'TEST', 60, 6, " + TEACHER + ", false, true, 'manual', 508001, 508001)");
        sql("INSERT INTO questions (id, statement_id, number, text, question_type, max_score, order_index) VALUES ("
                + QUESTION + ", " + STATEMENT + ", 1, 'Explique a diferenca entre local e global.', 'open', 6, 1)");
        teacherToken = jwtService.generateToken(TEACHER, List.of("TEACHER"));
        studentToken = jwtService.generateToken(STUDENT, List.of("STUDENT"));
        engineToken = jwtService.generateToken(ENGINE, List.of("INTEGRATION"));
    }

    @Test
    void anAnswerIsGradedOnceAndThenRectifiedWithTheFinalScoreRecomputed() {
        bearer(teacherToken).post().uri("/api/v1/statements/" + STATEMENT + "/approve").exchange().expectStatus().isOk();
        bearer(teacherToken).patch().uri("/api/v1/statements/" + STATEMENT + "/visibility?visible=true").exchange().expectStatus().isOk();

        AtomicLong simulation = new AtomicLong();
        bearer(studentToken).post().uri("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + STUDENT + ",\"statementId\":" + STATEMENT + "}").exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.id").value(value -> simulation.set(((Number) value).longValue()));
        AtomicLong answer = new AtomicLong();
        bearer(studentToken).post().uri("/api/v1/simulation-answers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"simulationId\":" + simulation.get() + ",\"questionId\":" + QUESTION + ",\"answerText\":\"A local vive na funcao.\"}")
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.id").value(value -> answer.set(((Number) value).longValue()));
        bearer(studentToken).post().uri("/api/v1/simulations/" + simulation.get() + "/submit").exchange().expectStatus().isOk();
        String grade = "/api/v1/simulation-answers/" + answer.get() + "/grade";

        // rectificar o que ainda não foi corrigido não é permitido; um aluno nunca corrige
        patch(engineToken, grade + "?rectify=true", 5.0).expectStatus().isEqualTo(409);
        patch(studentToken, grade, 5.0).expectStatus().isForbidden();

        // primeira correção (a do professor) e nota final da simulação
        patch(engineToken, grade, 3.0).expectStatus().isOk().expectBody().jsonPath("$.scoreObtained").isEqualTo(3.0);
        assertThat(finalScore(simulation.get())).isEqualTo(3.0);
        // corrigir outra vez sem dizer que é uma rectificação continua recusado
        patch(engineToken, grade, 4.0).expectStatus().isEqualTo(409);

        // rectificação: sobe a pontuação e a nota final acompanha
        patch(engineToken, grade + "?rectify=true", 5.0).expectStatus().isOk().expectBody().jsonPath("$.scoreObtained").isEqualTo(5.0);
        assertThat(finalScore(simulation.get())).isEqualTo(5.0);
        // o professor continua a poder rectificar uma resposta corrigida por pessoa ou pela IA (só a auto-correcção é restrita)
        patch(teacherToken, grade + "?rectify=true", 5.0).expectStatus().isOk();
        // repetir o mesmo valor não duplica nada (a reentrega do job)
        patch(engineToken, grade + "?rectify=true", 5.0).expectStatus().isOk();
        assertThat(finalScore(simulation.get())).isEqualTo(5.0);
        // nunca acima da cotação da questão; um aluno nunca rectifica
        patch(engineToken, grade + "?rectify=true", 7.0).expectStatus().isBadRequest();
        patch(studentToken, grade + "?rectify=true", 6.0).expectStatus().isForbidden();
        assertThat(finalScore(simulation.get())).isEqualTo(5.0);
    }

    /**
     * Vaga 1B, B1: o motor rectifica uma questão objectiva deferida em reclamação com {@code ?rectify=true}; a resposta está
     * {@code AUTO_GRADED}. Passa a {@code GRADED}, a nota final recalcula-se e o aluno vê a nota certa.
     */
    @Test
    void anAutoGradedObjectiveAnswerIsRectifiedByAnAppealAndTheStudentSeesTheNewScore() {
        // a prova só tem uma objectiva (cotação 6): a errada vale 0
        sql("DELETE FROM questions WHERE id = " + QUESTION);
        long objective = QUESTION + 1;
        sql("INSERT INTO questions (id, statement_id, number, text, question_type, max_score, order_index) VALUES ("
                + objective + ", " + STATEMENT + ", 1, 'Quanto e 2 + 2?', 'multiple_choice', 6, 1)");
        sql("INSERT INTO question_options (id, question_id, option_label, option_text, is_correct, order_index) VALUES "
                + "(" + objective + ", " + objective + ", 'A', '3', false, 1), (" + (objective + 1) + ", " + objective + ", 'B', '4', true, 2)");
        bearer(teacherToken).post().uri("/api/v1/statements/" + STATEMENT + "/approve").exchange().expectStatus().isOk();
        bearer(teacherToken).patch().uri("/api/v1/statements/" + STATEMENT + "/visibility?visible=true").exchange().expectStatus().isOk();
        AtomicLong simulation = new AtomicLong();
        bearer(studentToken).post().uri("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + STUDENT + ",\"statementId\":" + STATEMENT + "}").exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.id").value(value -> simulation.set(((Number) value).longValue()));
        AtomicLong answer = new AtomicLong();
        bearer(studentToken).post().uri("/api/v1/simulation-answers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"simulationId\":" + simulation.get() + ",\"questionId\":" + objective + ",\"selectedOptionId\":" + objective + "}")
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.id").value(value -> answer.set(((Number) value).longValue()));
        bearer(studentToken).post().uri("/api/v1/simulations/" + simulation.get() + "/submit").exchange().expectStatus().isOk();
        String grade = "/api/v1/simulation-answers/" + answer.get() + "/grade";
        assertThat(finalScore(simulation.get())).isEqualTo(0.0);

        // sem rectify continua recusado (não é uma primeira correção); o aluno nunca rectifica
        patch(engineToken, grade, 6.0).expectStatus().isEqualTo(409);
        patch(studentToken, grade + "?rectify=true", 6.0).expectStatus().isForbidden();
        // reteste F2: um professor não altera directamente a nota de uma objectiva auto-corrigida (só o motor e o administrador)
        patch(teacherToken, grade + "?rectify=true", 6.0).expectStatus().isEqualTo(409);
        assertThat(finalScore(simulation.get())).isEqualTo(0.0);
        // o administrador pode (a administração), e volta-se atrás para o motor rectificar a seguir
        String adminToken = jwtService.generateToken(ADMIN, List.of("ADMIN"));
        patch(adminToken, grade + "?rectify=true", 2.0).expectStatus().isOk().expectBody().jsonPath("$.reviewStatus").isEqualTo("GRADED");
        assertThat(finalScore(simulation.get())).isEqualTo(2.0);
        sql("UPDATE simulation_answers SET review_status = 'AUTO_GRADED', score_obtained = 0 WHERE id = " + answer.get());
        sql("UPDATE simulations SET final_score = 0 WHERE id = " + simulation.get());
        // a reclamação deferida: o motor rectifica a objectiva
        patch(engineToken, grade + "?rectify=true", 6.0).expectStatus().isOk()
                .expectBody().jsonPath("$.scoreObtained").isEqualTo(6.0).jsonPath("$.reviewStatus").isEqualTo("GRADED");
        assertThat(finalScore(simulation.get())).isEqualTo(6.0);
        bearer(studentToken).get().uri("/api/v1/simulations/" + simulation.get() + "/result").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.finalScore").isEqualTo(6.0)
                .jsonPath("$.questions[0].scoreObtained").isEqualTo(6.0)
                .jsonPath("$.questions[0].reviewStatus").isEqualTo("GRADED");
        // repetir o mesmo valor (a reentrega do job) não duplica nada; nunca acima da cotação
        patch(engineToken, grade + "?rectify=true", 6.0).expectStatus().isOk();
        patch(engineToken, grade + "?rectify=true", 7.0).expectStatus().isBadRequest();
        assertThat(finalScore(simulation.get())).isEqualTo(6.0);
    }

    private WebTestClient.ResponseSpec patch(String token, String uri, double score) {
        return bearer(token).patch().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"scoreObtained\":" + score + "}").exchange();
    }

    private double finalScore(long simulation) {
        double[] score = new double[1];
        bearer(studentToken).get().uri("/api/v1/simulations/" + simulation).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.finalScore").value(value -> score[0] = ((Number) value).doubleValue());
        return score[0];
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
