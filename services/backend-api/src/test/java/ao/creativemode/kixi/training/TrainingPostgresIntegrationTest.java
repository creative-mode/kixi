package ao.creativemode.kixi.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import com.fasterxml.jackson.databind.JsonNode;
import ao.creativemode.kixi.identity.service.JwtService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * O fluxo do treino por temas contra um PostgreSQL a sério (kixi#729): temas, etiquetagem, matriz, plano e treino montado,
 * e a garantia de que o enunciado virtual do treino é só do aluno dono e não aparece em lado nenhum.
 *
 * <p>Fixture: a disciplina SUBJECT, a sala ROOM (enunciado ROOM_ST, com a prova «real») e os enunciados de provas anteriores
 * PREV_A, PREV_B, PREV_D (sala encerrada) que alimentam o treino, mais os que NÃO podem alimentá-lo: HIDDEN (não publicado),
 * PREV_C (sala ainda não encerrada), OTHER_SUBJECT e o da própria sala.</p>
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
class TrainingPostgresIntegrationTest {

    private static final long TEACHER = 729001L;
    private static final long STUDENT = 729002L;
    private static final long OTHER_STUDENT = 729003L;
    private static final long OTHER_TEACHER = 729004L;
    private static final long ADMIN = 729005L;
    private static final long SUBJECT = 729001L;
    private static final long OTHER_SUBJECT_ID = 729002L;
    private static final long ROOM = 729001L;
    private static final long ROOM_ST = 729001L;
    private static final long PREV_A = 729002L;
    private static final long PREV_B = 729003L;
    private static final long PREV_C = 729004L;
    private static final long PREV_D = 729005L;
    private static final long HIDDEN = 729006L;
    private static final long OTHER_SUBJECT_ST = 729007L;
    private static final long PREV_E = 729008L;
    private static final long[] STATEMENTS = {ROOM_ST, PREV_A, PREV_B, PREV_C, PREV_D, HIDDEN, OTHER_SUBJECT_ST, PREV_E};
    private static final String ALL_ACCOUNTS = TEACHER + "," + STUDENT + "," + OTHER_STUDENT + "," + OTHER_TEACHER + "," + ADMIN;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DatabaseClient db;

    @Autowired
    private JwtService jwt;

    private WebTestClient client;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("TrainingPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity())
                .configureClient().responseTimeout(Duration.ofSeconds(30)).build();
        clean();
        sql("INSERT INTO subjects (id, code, name) VALUES (" + SUBJECT + ", 'IT729A', 'Matemática IT'), ("
                + OTHER_SUBJECT_ID + ", 'IT729B', 'Física IT')");
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, active) SELECT i, 'it-729-' || i, 'it-729-' || i || '@example.com', 'x', true, true "
                + "FROM unnest(ARRAY[" + ALL_ACCOUNTS + "]::bigint[]) i");
        role(TEACHER, "TEACHER");
        role(OTHER_TEACHER, "TEACHER");
        role(STUDENT, "STUDENT");
        role(OTHER_STUDENT, "STUDENT");
        role(ADMIN, "ADMIN");
        for (long id : STATEMENTS) {
            long subject = id == OTHER_SUBJECT_ST ? OTHER_SUBJECT_ID : SUBJECT;
            sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, subject_id, created_by, visible, needs_review, source) VALUES ("
                    + id + ", 'Prova it-729 #" + id + "', 'TEST', 90, 20, " + subject + ", " + TEACHER + ", true, false, 'manual')");
        }
        sql("UPDATE statements SET visible = FALSE WHERE id = " + HIDDEN);
        sql("UPDATE statements SET created_at = '2022-05-01', title = 'Prova 2022' WHERE id = " + PREV_A);
        sql("UPDATE statements SET created_at = '2023-05-01', title = 'Prova 2023' WHERE id = " + PREV_B);
        sql("UPDATE statements SET created_at = '2021-05-01', title = 'Prova 2021 (sala encerrada)' WHERE id = " + PREV_D);

        // a sala da prova real: o aluno STUDENT é convidado, o OTHER_STUDENT não
        sql("INSERT INTO exam_rooms (id, statement_id, teacher_account_id, starts_at, ends_at, duration_minutes, status) VALUES ("
                + ROOM + ", " + ROOM_ST + ", " + TEACHER + ", now() + interval '7 days', now() + interval '8 days', 90, 'OPEN')");
        sql("INSERT INTO exam_room_participants (exam_room_id, account_id) VALUES (" + ROOM + ", " + STUDENT + ")");
        // PREV_C tem uma sala ainda não encerrada; PREV_D tem uma sala já encerrada. Ambas retêm o enunciado: o D volta a ser publicado.
        sql("INSERT INTO exam_rooms (id, statement_id, teacher_account_id, starts_at, ends_at, duration_minutes, status) VALUES "
                + "(729002, " + PREV_C + ", " + TEACHER + ", now(), now() + interval '1 day', 60, 'DRAFT'), "
                + "(729003, " + PREV_D + ", " + TEACHER + ", now() - interval '2 day', now() - interval '1 day', 60, 'CLOSED')");
        sql("UPDATE statements SET visible = TRUE WHERE id IN (" + PREV_C + ", " + PREV_D + ")");
        // PREV_E é o enunciado de uma avaliação do plano do trimestre (pode ainda estar por fazer): nunca alimenta o treino
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (729001, 'IT729', 'Instituição IT 729', now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id) VALUES (729001, 'IT729', 'Curso IT 729', 729001)");
        sql("INSERT INTO terms (id, number, name) VALUES (729001, 729, 'Trimestre IT 729')");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id) VALUES (729001, 'IT729A', 10, 729001, (SELECT MIN(id) FROM school_years), 729001)");
        sql("INSERT INTO term_plans (id, class_id, subject_id, term_id, created_by) VALUES (729001, 729001, " + SUBJECT + ", 729001, " + TEACHER + ")");
        sql("INSERT INTO term_assessments (plan_id, kind, seq, title, planned_date, statement_id) VALUES (729001, 'P1', 1, 'P1 IT', current_date, " + PREV_E + ")");

        // questões: Q1..Q3 (múltipla escolha) e Q11 sobre Funções; Q4 e o item Q6 do grupo Q5 sobre Geometria
        question(1, PREV_A, 1, "multiple_choice", null);
        question(2, PREV_A, 2, "multiple_choice", null);
        question(3, PREV_B, 1, "multiple_choice", null);
        question(11, PREV_D, 1, "multiple_choice", null);
        question(4, PREV_B, 2, "short_answer", null);
        question(5, PREV_B, 3, "group", null);
        question(6, PREV_B, 301, "short_answer", 5L);
        sql("UPDATE questions SET label = 'a', model_answer = 'resposta' WHERE id = 6");
        question(7, HIDDEN, 1, "multiple_choice", null);
        question(8, OTHER_SUBJECT_ST, 1, "multiple_choice", null);
        question(9, ROOM_ST, 1, "multiple_choice", null);
        question(10, PREV_C, 1, "multiple_choice", null);
        question(12, PREV_E, 1, "multiple_choice", null);
        for (long q : new long[] {1, 2, 3, 11, 7, 8, 9, 10, 12}) {
            sql("INSERT INTO question_options (question_id, option_label, option_text, is_correct, order_index) VALUES "
                    + "(" + (729000 + q) + ", 'A', 'certa', true, 0), (" + (729000 + q) + ", 'B', 'errada', false, 1)");
        }
        sql("INSERT INTO question_images (question_id, image_url, storage_key, order_index) VALUES (729001, '/uploads/questions/x/it.png', 'questions/x/it.png', 0)");
    }

    @AfterEach
    void clean() {
        sql("DELETE FROM simulation_answers WHERE simulation_id IN (SELECT id FROM simulations WHERE account_id IN (" + ALL_ACCOUNTS + "))");
        sql("DELETE FROM simulations WHERE account_id IN (" + ALL_ACCOUNTS + ")");
        sql("DELETE FROM statements WHERE practice_owner_account_id IN (" + ALL_ACCOUNTS + ")");
        sql("DELETE FROM exam_rooms WHERE id IN (729001, 729002, 729003)");
        sql("DELETE FROM term_assessments WHERE plan_id = 729001");
        sql("DELETE FROM term_plans WHERE id = 729001");
        sql("DELETE FROM classes WHERE id = 729001");
        sql("DELETE FROM courses WHERE id = 729001");
        sql("DELETE FROM institutions WHERE id = 729001");
        sql("DELETE FROM terms WHERE id = 729001");
        sql("DELETE FROM statements WHERE id BETWEEN 729001 AND 729008");
        sql("DELETE FROM subject_topics WHERE subject_id IN (" + SUBJECT + "," + OTHER_SUBJECT_ID + ")");
        sql("DELETE FROM subjects WHERE id IN (" + SUBJECT + "," + OTHER_SUBJECT_ID + ")");
        sql("DELETE FROM user_stats WHERE account_id IN (" + ALL_ACCOUNTS + ")");
        sql("DELETE FROM account_roles WHERE account_id IN (" + ALL_ACCOUNTS + ")");
        sql("DELETE FROM accounts WHERE id IN (" + ALL_ACCOUNTS + ")");
    }

    @Test
    void fromTopicsToAPracticeSimulationOnAPrivateVirtualStatement() {
        // ── temas: criar, duplicado, listar ────────────────────────────────
        long functions = createTopic("Funções", "função, gráfico");
        post(TEACHER, "TEACHER", "/api/v1/subjects/" + SUBJECT + "/topics", "{\"name\":\"funcoes\"}").expectStatus().isEqualTo(409);
        post(STUDENT, "STUDENT", "/api/v1/subjects/" + SUBJECT + "/topics", "{\"name\":\"Outro\"}").expectStatus().isForbidden();
        long geometry = createTopic("Geometria", "triângulo");

        // ── etiquetar: manualmente a Q1 e Q2, automaticamente o resto ───────
        put(TEACHER, "TEACHER", "/api/v1/questions/729001/topics", "{\"topicIds\":[" + functions + "]}").expectStatus().isOk();
        put(TEACHER, "TEACHER", "/api/v1/questions/729002/topics", "{\"topicIds\":[" + functions + "]}").expectStatus().isOk();
        // um tema de outra disciplina não serve
        long foreign = topicInOtherSubject();
        put(TEACHER, "TEACHER", "/api/v1/questions/729001/topics", "{\"topicIds\":[" + foreign + "]}").expectStatus().isBadRequest();
        // a etiquetagem automática nunca sobrepõe a manual: a Q1 diz «triângulo» mas já tem tema (Funções)
        sql("UPDATE questions SET text = 'Calcule a área do triângulo' WHERE id = 729001");
        sql("UPDATE questions SET text = 'Esboce o gráfico da função f' WHERE id IN (729003, 729011, 729007, 729009, 729010, 729008, 729012)");
        sql("UPDATE questions SET text = 'Calcule a área do triângulo' WHERE id = 729004");
        sql("UPDATE questions SET text = 'Considere o triângulo ABC.' WHERE id = 729005");
        sql("UPDATE questions SET text = 'a) Justifique.' WHERE id = 729006");
        JsonNode untouched = json(post(TEACHER, "TEACHER", "/api/v1/statements/" + PREV_A + "/topics/auto-tag", null).expectStatus().isOk());
        assertThat(untouched.get("tagged").asInt()).isZero();
        assertThat(count("SELECT COUNT(*) FROM question_topics WHERE question_id = 729001 AND source = 'MANUAL' AND topic_id = " + functions)).isEqualTo(1);
        // PREV_B: Q3 (funções), Q4 (geometria) e o item Q6, pelo enunciado comum do grupo; o cabeçalho Q5 não se etiqueta
        JsonNode tagged = json(post(TEACHER, "TEACHER", "/api/v1/statements/" + PREV_B + "/topics/auto-tag", null).expectStatus().isOk());
        assertThat(tagged.get("tagged").asInt()).isEqualTo(3);
        assertThat(tagged.get("untagged").asInt()).isZero();
        JsonNode again = json(post(TEACHER, "TEACHER", "/api/v1/statements/" + PREV_B + "/topics/auto-tag", null).expectStatus().isOk());
        assertThat(again.get("tagged").asInt()).isZero();
        for (long st : new long[] {HIDDEN, ROOM_ST, PREV_C, PREV_D, PREV_E}) {
            post(ADMIN, "ADMIN", "/api/v1/statements/" + st + "/topics/auto-tag", null).expectStatus().isOk();
        }
        // Q8 é de outra disciplina: os temas desta não a apanham
        JsonNode other = json(post(ADMIN, "ADMIN", "/api/v1/statements/" + OTHER_SUBJECT_ST + "/topics/auto-tag", null).expectStatus().isOk());
        assertThat(other.get("tagged").asInt()).isZero();
        assertThat(other.get("untagged").asInt()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM question_topics WHERE question_id = 729008")).isZero();
        assertThat(count("SELECT COUNT(*) FROM question_topics WHERE question_id = 729003 AND source = 'AUTO'")).isEqualTo(1);
        // Q5 é um cabeçalho de grupo: nunca tem tema
        assertThat(count("SELECT COUNT(*) FROM question_topics WHERE question_id = 729005")).isZero();

        JsonNode topics = json(get(STUDENT, "STUDENT", "/api/v1/subjects/" + SUBJECT + "/topics").expectStatus().isOk());
        assertThat(topics).hasSize(2);
        assertThat(topics.findValuesAsText("name")).containsExactlyInAnyOrder("Funções", "Geometria");

        // ── matriz: só o dono escreve; o aluno convidado lê ─────────────────
        String body = "{\"title\":\"Matriz do 1.º trimestre\",\"items\":["
                + "{\"topicId\":" + functions + ",\"questionCount\":4,\"points\":12,\"cognitiveLevel\":\"conhecimento\",\"questionTypes\":[\"múltipla escolha\"]},"
                + "{\"topicName\":\"Geometria\",\"questionCount\":2,\"points\":8,\"cognitiveLevel\":\"ANALYSIS\"}]}";
        put(OTHER_TEACHER, "TEACHER", "/api/v1/exam-rooms/" + ROOM + "/matrix", body).expectStatus().isForbidden();
        put(TEACHER, "TEACHER", "/api/v1/exam-rooms/" + ROOM + "/matrix",
                body.replace("\"points\":8", "\"points\":7")).expectStatus().isBadRequest();
        JsonNode matrix = json(put(TEACHER, "TEACHER", "/api/v1/exam-rooms/" + ROOM + "/matrix", body).expectStatus().isOk());
        assertThat(matrix.get("totalPoints").asDouble()).isEqualTo(20.0);
        assertThat(matrix.get("items").get(0).get("topicName").asText()).isEqualTo("Funções");
        assertThat(matrix.get("items").get(0).get("cognitiveLevel").asText()).isEqualTo("KNOWLEDGE");
        assertThat(matrix.get("items").get(1).get("topicId").asLong()).isEqualTo(geometry);
        assertThat(get(STUDENT, "STUDENT", "/api/v1/exam-rooms/" + ROOM + "/matrix").expectStatus().isOk()).isNotNull();
        get(OTHER_STUDENT, "STUDENT", "/api/v1/exam-rooms/" + ROOM + "/matrix").expectStatus().isNotFound();
        // substituir: a segunda escrita troca a matriz, sem acumular
        put(TEACHER, "TEACHER", "/api/v1/exam-rooms/" + ROOM + "/matrix", body).expectStatus().isOk();
        assertThat(count("SELECT COUNT(*) FROM exam_matrix_items")).isEqualTo(2);
        // um tema que está numa matriz não se apaga, e o nome de um tema novo tem limite
        delete(TEACHER, "TEACHER", "/api/v1/topics/" + functions).expectStatus().isEqualTo(409);
        put(TEACHER, "TEACHER", "/api/v1/exam-rooms/" + ROOM + "/matrix",
                "{\"items\":[{\"topicName\":\"" + "x".repeat(201) + "\",\"questionCount\":1}]}").expectStatus().isBadRequest();
        put(TEACHER, "TEACHER", "/api/v1/exam-rooms/" + ROOM + "/matrix",
                "{\"items\":[{\"topicId\":" + functions + ",\"questionCount\":101}]}").expectStatus().isBadRequest();
        put(TEACHER, "TEACHER", "/api/v1/exam-rooms/" + ROOM + "/matrix",
                "{\"totalPoints\":10000,\"items\":[{\"topicId\":" + functions + ",\"questionCount\":1}]}").expectStatus().isBadRequest();

        // ── plano: só o que pode sair, e a prova real nunca ─────────────────
        get(OTHER_STUDENT, "STUDENT", "/api/v1/training/rooms/" + ROOM + "/plan").expectStatus().isNotFound();
        JsonNode plan = json(get(STUDENT, "STUDENT", "/api/v1/training/rooms/" + ROOM + "/plan").expectStatus().isOk());
        assertThat(plan.get("subjectName").asText()).isEqualTo("Matemática IT");
        assertThat(plan.get("durationMinutes").asInt()).isEqualTo(90);
        JsonNode fItem = plan.get("items").get(0);
        JsonNode gItem = plan.get("items").get(1);
        // Funções: Q1, Q2 (PREV_A), Q3 (PREV_B) e Q11 (PREV_D, sala encerrada); NUNCA Q7 (não publicada), Q8 (outra disciplina),
        // Q9 (a própria prova), Q10 (sala por encerrar) nem Q12 (avaliação do plano do trimestre)
        assertThat(count("SELECT COUNT(*) FROM question_topics WHERE question_id = 729012")).as("Q12 está etiquetada").isEqualTo(1);
        assertThat(fItem.get("topicId").asLong()).isEqualTo(functions);
        assertThat(fItem.get("available").asInt()).isEqualTo(4);
        assertThat(fItem.get("attempted").asInt()).isZero();
        List<Long> sourceStatements = new ArrayList<>();
        fItem.get("sources").forEach(s -> sourceStatements.add(s.get("statementId").asLong()));
        assertThat(sourceStatements).containsExactly(PREV_B, PREV_A, PREV_D);
        assertThat(fItem.get("sources").get(0).get("year").asInt()).isEqualTo(2023);
        // Geometria: Q4 e o item Q6 (o cabeçalho do grupo não é respondível)
        assertThat(gItem.get("available").asInt()).isEqualTo(2);

        // ── treino: 3 de Funções, 5 de Geometria (só há 2) ───────────────────
        String request = "{\"roomId\":" + ROOM + ",\"items\":[{\"topicId\":" + functions + ",\"count\":3},{\"topicId\":" + geometry + ",\"count\":5}]}";
        post(OTHER_STUDENT, "STUDENT", "/api/v1/simulations/practice/matrix", request).expectStatus().isNotFound();
        JsonNode practice = json(post(STUDENT, "STUDENT", "/api/v1/simulations/practice/matrix", request).expectStatus().isCreated());
        assertThat(practice.get("allocation").get(0).get("requested").asInt()).isEqualTo(3);
        assertThat(practice.get("allocation").get(0).get("provided").asInt()).isEqualTo(3);
        assertThat(practice.get("allocation").get(1).get("requested").asInt()).isEqualTo(5);
        assertThat(practice.get("allocation").get(1).get("provided").asInt()).isEqualTo(2);
        long simulationId = practice.get("simulation").get("id").asLong();
        long virtual = practice.get("simulation").get("statement").get("id").asLong();
        assertThat(practice.get("simulation").get("statement").get("title").asText()).isEqualTo("Treino · Matriz do 1.º trimestre");
        assertThat(practice.get("simulation").get("status").asText()).isEqualTo("IN_PROGRESS");

        Map<String, Object> row = one("SELECT practice_owner_account_id AS owner, visible, source, subject_id, duration_minutes FROM statements WHERE id = " + virtual);
        assertThat(row.get("owner")).isEqualTo(STUDENT);
        assertThat(row.get("visible")).isEqualTo(false);
        assertThat(row.get("source")).isEqualTo("practice");
        assertThat(((Number) row.get("subject_id")).longValue()).isEqualTo(SUBJECT);
        // 3 + 2 = 5 questões de 6 na matriz: 90 * 5 / 6 = 75 minutos
        assertThat(((Number) row.get("duration_minutes")).intValue()).isEqualTo(75);
        assertThat(count("SELECT title IS NOT NULL FROM simulations WHERE id = " + simulationId + " AND title = 'Treino · Matriz do 1.º trimestre'")).isEqualTo(1);
        // cópias: 3 de Funções + Q4 + cabeçalho do grupo + o item Q6 = 6 linhas
        assertThat(count("SELECT COUNT(*) FROM questions WHERE statement_id = " + virtual)).isEqualTo(6);
        assertThat(count("SELECT COUNT(*) FROM questions WHERE statement_id = " + virtual + " AND source_question_id IN (729007, 729008, 729009, 729010)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM questions c JOIN questions h ON h.id = c.parent_id WHERE c.statement_id = " + virtual
                + " AND h.statement_id = " + virtual + " AND h.question_type = 'group' AND c.source_question_id = 729006")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM question_options o JOIN questions c ON c.id = o.question_id WHERE c.statement_id = " + virtual)).isEqualTo(6);
        assertThat(count("SELECT COUNT(*) FROM question_options o JOIN questions c ON c.id = o.question_id WHERE c.statement_id = " + virtual + " AND o.is_correct")).isEqualTo(3);
        // as imagens são do mesmo ficheiro (sem duplicar o binário): a cópia da Q1, se foi escolhida, aponta para a mesma chave
        long q1Chosen = count("SELECT COUNT(*) FROM questions WHERE statement_id = " + virtual + " AND source_question_id = 729001");
        assertThat(count("SELECT COUNT(*) FROM question_images i JOIN questions c ON c.id = i.question_id WHERE c.statement_id = " + virtual
                + " AND i.storage_key = 'questions/x/it.png' AND i.image_url = '/uploads/questions/x/it.png'")).isEqualTo(q1Chosen);

        // ── o enunciado virtual é só do aluno dono ──────────────────────────
        JsonNode full = json(get(STUDENT, "STUDENT", "/api/v1/statements/" + virtual + "/full").expectStatus().isOk());
        assertThat(full.toString()).doesNotContain("\"isCorrect\":true");
        get(STUDENT, "STUDENT", "/api/v1/statements/" + virtual).expectStatus().isOk();
        get(OTHER_STUDENT, "STUDENT", "/api/v1/statements/" + virtual + "/full").expectStatus().isNotFound();
        get(OTHER_STUDENT, "STUDENT", "/api/v1/statements/" + virtual).expectStatus().isNotFound();
        get(TEACHER, "TEACHER", "/api/v1/statements/" + virtual + "/full").expectStatus().isNotFound();
        get(ADMIN, "ADMIN", "/api/v1/statements/" + virtual).expectStatus().isNotFound();
        get(OTHER_STUDENT, "STUDENT", "/api/v1/simulations/" + simulationId).expectStatus().isNotFound();
        get(TEACHER, "TEACHER", "/api/v1/statements/" + virtual + "/questions").expectStatus().isNotFound();
        for (String viewer : new String[] {"TEACHER", "ADMIN", "STUDENT"}) {
            long account = viewer.equals("TEACHER") ? TEACHER : viewer.equals("ADMIN") ? ADMIN : OTHER_STUDENT;
            assertThat(get(account, viewer, "/api/v1/statements").expectStatus().isOk().expectBody(String.class).returnResult()
                    .getResponseBody()).doesNotContain("\"id\":" + virtual + ",").doesNotContain("Treino ·");
            assertThat(get(account, viewer, "/api/v1/statements/search?query=Treino").expectStatus().isOk().expectBody(String.class)
                    .returnResult().getResponseBody()).doesNotContain("Treino ·");
            assertThat(get(account, viewer, "/api/v1/statements/catalog").expectStatus().isOk().expectBody(String.class)
                    .returnResult().getResponseBody()).doesNotContain("Treino ·");
            assertThat(get(account, viewer, "/api/v1/statements/by-subject/" + SUBJECT).expectStatus().isOk().expectBody(String.class)
                    .returnResult().getResponseBody()).doesNotContain("Treino ·");
        }
        // nem como candidato a nova sala nem a novo treino
        assertThat(count("SELECT COUNT(*) FROM statements WHERE practice_owner_account_id IS NULL AND source = 'practice'")).isZero();

        // ── responder e terminar pelo caminho normal; o plano passa a contar o histórico ─────
        List<Map<String, Object>> clones = rows("SELECT c.id, c.source_question_id AS src, "
                + "(SELECT o.id FROM question_options o WHERE o.question_id = c.id AND o.is_correct) AS right_option "
                + "FROM questions c WHERE c.statement_id = " + virtual + " AND c.question_type = 'multiple_choice' ORDER BY c.id");
        assertThat(clones).hasSize(3);
        for (Map<String, Object> clone : clones) {
            post(STUDENT, "STUDENT", "/api/v1/simulation-answers", "{\"simulationId\":" + simulationId + ",\"questionId\":" + clone.get("id")
                    + ",\"selectedOptionId\":" + clone.get("right_option") + "}").expectStatus().isCreated();
        }
        post(STUDENT, "STUDENT", "/api/v1/simulations/" + simulationId + "/submit", null).expectStatus().isOk();
        JsonNode result = json(get(STUDENT, "STUDENT", "/api/v1/simulations/" + simulationId + "/result").expectStatus().isOk());
        assertThat(result.get("status").asText()).isEqualTo("FINISHED");

        JsonNode after = json(get(STUDENT, "STUDENT", "/api/v1/training/rooms/" + ROOM + "/plan").expectStatus().isOk());
        JsonNode functionsAfter = after.get("items").get(0);
        assertThat(functionsAfter.get("available").asInt()).as("o enunciado virtual nunca entra no conjunto").isEqualTo(4);
        assertThat(functionsAfter.get("attempted").asInt()).as("tentadas, mapeadas pela questão de origem").isEqualTo(3);
        assertThat(functionsAfter.get("correct").asInt()).isEqualTo(3);

        // o ranking e a pauta não veem o treino
        assertThat(count("SELECT COUNT(*) FROM simulations s JOIN statements st ON st.id = s.statement_id "
                + "WHERE st.practice_owner_account_id IS NOT NULL AND st.id = " + virtual)).isEqualTo(1);

        // ── um segundo treino prefere as que o aluno nunca tentou ────────────
        JsonNode second = json(post(STUDENT, "STUDENT", "/api/v1/simulations/practice/matrix",
                "{\"roomId\":" + ROOM + ",\"items\":[{\"topicId\":" + functions + ",\"count\":1}]}").expectStatus().isCreated());
        long secondVirtual = second.get("simulation").get("statement").get("id").asLong();
        List<Map<String, Object>> picked = rows("SELECT source_question_id AS src FROM questions WHERE statement_id = " + secondVirtual);
        assertThat(picked).hasSize(1);
        assertThat(((Number) picked.get(0).get("src")).longValue())
                .as("a única questão de Funções que o aluno ainda não tinha tentado").isEqualTo(untried(clones));
    }

    @Test
    void importAndDeleteTheMatrixAndNoPoolMeans400() {
        String csv = "Tema;Questões;Cotação;Nível\nFunções;3;12;conhecimento\nGeometria;2;8;Análise\n";
        long functionsTopic = createTopic("Funções", null);
        post(OTHER_TEACHER, "TEACHER", "/api/v1/exam-rooms/" + ROOM + "/matrix/import", csv, "text/csv").expectStatus().isForbidden();
        post(TEACHER, "TEACHER", "/api/v1/exam-rooms/" + ROOM + "/matrix/import", "Funções;três", "text/csv").expectStatus().isBadRequest();
        JsonNode matrix = json(post(TEACHER, "TEACHER", "/api/v1/exam-rooms/" + ROOM + "/matrix/import", csv, "text/csv").expectStatus().isOk());
        assertThat(matrix.get("items")).hasSize(2);
        // «Funções» já existia: reutiliza o tema, não cria outro; «Geometria» criou-se
        assertThat(matrix.get("items").get(0).get("topicId").asLong()).isEqualTo(functionsTopic);
        assertThat(count("SELECT COUNT(*) FROM subject_topics WHERE subject_id = " + SUBJECT + " AND deleted_at IS NULL")).isEqualTo(2);
        assertThat(matrix.get("items").get(1).get("cognitiveLevel").asText()).isEqualTo("ANALYSIS");

        // sem questões etiquetadas não há treino: 400
        post(STUDENT, "STUDENT", "/api/v1/simulations/practice/matrix",
                "{\"roomId\":" + ROOM + ",\"items\":[{\"topicId\":" + functionsTopic + ",\"count\":2}]}").expectStatus().isBadRequest();
        // um tema que não está na matriz: 400
        post(STUDENT, "STUDENT", "/api/v1/simulations/practice/matrix",
                "{\"roomId\":" + ROOM + ",\"items\":[{\"topicId\":1,\"count\":2}]}").expectStatus().isBadRequest();

        delete(OTHER_TEACHER, "TEACHER", "/api/v1/exam-rooms/" + ROOM + "/matrix").expectStatus().isForbidden();
        delete(TEACHER, "TEACHER", "/api/v1/exam-rooms/" + ROOM + "/matrix").expectStatus().isNoContent();
        get(TEACHER, "TEACHER", "/api/v1/exam-rooms/" + ROOM + "/matrix").expectStatus().isNotFound();
        delete(TEACHER, "TEACHER", "/api/v1/exam-rooms/" + ROOM + "/matrix").expectStatus().isNotFound();
        get(STUDENT, "STUDENT", "/api/v1/training/rooms/" + ROOM + "/plan").expectStatus().isNotFound();
    }

    @Test
    void aTeacherCannotHoldMoreThanTwentyOpenPracticesOfOneMatrix() {
        long functions = createTopic("Funções", "função");
        for (long q : new long[] {1, 2, 3}) {
            put(TEACHER, "TEACHER", "/api/v1/questions/" + (729000 + q) + "/topics", "{\"topicIds\":[" + functions + "]}").expectStatus().isOk();
        }
        put(TEACHER, "TEACHER", "/api/v1/exam-rooms/" + ROOM + "/matrix",
                "{\"items\":[{\"topicId\":" + functions + ",\"questionCount\":2}]}").expectStatus().isOk();
        String request = "{\"roomId\":" + ROOM + ",\"items\":[{\"topicId\":" + functions + ",\"count\":1}]}";
        long first = 0;
        for (int i = 0; i < 20; i++) {
            long id = json(post(STUDENT, "STUDENT", "/api/v1/simulations/practice/matrix", request).expectStatus().isCreated())
                    .get("simulation").get("id").asLong();
            if (i == 0) first = id;
        }
        JsonNode refused = json(post(STUDENT, "STUDENT", "/api/v1/simulations/practice/matrix", request).expectStatus().isEqualTo(409));
        assertThat(refused.get("detail").asText()).isEqualTo("Termina ou cancela um treino antes de começar outro.");
        // mais de 100 questões no total: 400 antes de clonar
        post(STUDENT, "STUDENT", "/api/v1/simulations/practice/matrix",
                "{\"roomId\":" + ROOM + ",\"items\":[{\"topicId\":" + functions + ",\"count\":100},{\"topicId\":1,\"count\":1}]}")
                .expectStatus().isBadRequest();
        // cancelar um treino liberta a vaga
        put(STUDENT, "STUDENT", "/api/v1/simulations/" + first, "{\"accountId\":" + STUDENT + ",\"statementId\":"
                + one("SELECT statement_id AS s FROM simulations WHERE id = " + first).get("s") + ",\"status\":\"CANCELLED\"}").expectStatus().isOk();
        post(STUDENT, "STUDENT", "/api/v1/simulations/practice/matrix", request).expectStatus().isCreated();
    }

    @Test
    void topicsCanBeEditedAndSoftDeletedAndTheNameCanBeReused() {
        long id = createTopic("Funções", "função");
        put(TEACHER, "TEACHER", "/api/v1/topics/" + id, "{\"name\":\"Funções reais\",\"keywords\":\"função real\"}").expectStatus().isOk();
        // os temas são globais: só o autor ou um administrador os altera
        put(OTHER_TEACHER, "TEACHER", "/api/v1/topics/" + id, "{\"name\":\"Outro nome\"}").expectStatus().isForbidden();
        delete(OTHER_TEACHER, "TEACHER", "/api/v1/topics/" + id).expectStatus().isForbidden();
        put(ADMIN, "ADMIN", "/api/v1/topics/" + id, "{\"name\":\"Funções reais\",\"keywords\":\"função real\"}").expectStatus().isOk();
        post(TEACHER, "TEACHER", "/api/v1/subjects/" + SUBJECT + "/topics", "{\"name\":\"" + "x".repeat(201) + "\"}").expectStatus().isBadRequest();
        long other = createTopic("Geometria", null);
        put(TEACHER, "TEACHER", "/api/v1/topics/" + other, "{\"name\":\"funcoes reais\"}").expectStatus().isEqualTo(409);
        // o mesmo nome noutra classe é outro tema
        post(TEACHER, "TEACHER", "/api/v1/subjects/" + SUBJECT + "/topics", "{\"name\":\"Funções reais\",\"grade\":10}").expectStatus().isCreated();
        JsonNode forGrade = json(get(STUDENT, "STUDENT", "/api/v1/subjects/" + SUBJECT + "/topics?grade=11").expectStatus().isOk());
        assertThat(forGrade).hasSize(2);
        JsonNode forTen = json(get(STUDENT, "STUDENT", "/api/v1/subjects/" + SUBJECT + "/topics?grade=10").expectStatus().isOk());
        assertThat(forTen).hasSize(3);
        delete(TEACHER, "TEACHER", "/api/v1/topics/" + id).expectStatus().isNoContent();
        delete(TEACHER, "TEACHER", "/api/v1/topics/" + id).expectStatus().isNotFound();
        post(TEACHER, "TEACHER", "/api/v1/subjects/" + SUBJECT + "/topics", "{\"name\":\"Funções reais\"}").expectStatus().isCreated();
        get(STUDENT, "STUDENT", "/api/v1/subjects/999999/topics").expectStatus().isNotFound();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private long untried(List<Map<String, Object>> clones) {
        List<Long> tried = clones.stream().map(c -> ((Number) c.get("src")).longValue()).toList();
        return List.of(729001L, 729002L, 729003L, 729011L).stream().filter(q -> !tried.contains(q)).findFirst().orElseThrow();
    }

    private long createTopic(String name, String keywords) {
        String body = "{\"name\":\"" + name + "\"" + (keywords == null ? "" : ",\"keywords\":\"" + keywords + "\"") + "}";
        return json(post(TEACHER, "TEACHER", "/api/v1/subjects/" + SUBJECT + "/topics", body).expectStatus().isCreated()).get("id").asLong();
    }

    private long topicInOtherSubject() {
        return json(post(TEACHER, "TEACHER", "/api/v1/subjects/" + OTHER_SUBJECT_ID + "/topics", "{\"name\":\"Cinemática\"}")
                .expectStatus().isCreated()).get("id").asLong();
    }

    private void question(long suffix, long statement, int number, String type, Long parent) {
        sql("INSERT INTO questions (id, statement_id, number, text, question_type, max_score, order_index, parent_id) VALUES ("
                + (729000 + suffix) + ", " + statement + ", " + number + ", 'q" + suffix + "', '" + type + "', 2, " + number + ", "
                + (parent == null ? "NULL" : String.valueOf(729000 + parent)) + ")");
    }

    private JsonNode json(WebTestClient.ResponseSpec spec) {
        return spec.expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec get(long account, String role, String uri) {
        return bearer(account, role).get().uri(uri).exchange();
    }

    private WebTestClient.ResponseSpec post(long account, String role, String uri, String body) {
        return post(account, role, uri, body, "application/json");
    }

    private WebTestClient.ResponseSpec post(long account, String role, String uri, String body, String contentType) {
        WebTestClient.RequestBodySpec spec = bearer(account, role).post().uri(uri).contentType(MediaType.parseMediaType(contentType));
        return (body == null ? spec : spec.bodyValue(body)).exchange();
    }

    private WebTestClient.ResponseSpec put(long account, String role, String uri, String body) {
        return bearer(account, role).put().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec delete(long account, String role, String uri) {
        return bearer(account, role).delete().uri(uri).exchange();
    }

    private WebTestClient bearer(long account, String role) {
        String token = jwt.generateToken(account, List.of(role));
        return client.mutate().defaultHeaders(headers -> headers.setBearerAuth(token)).build();
    }

    private void role(long account, String role) {
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + account + ", id FROM roles WHERE name = '" + role + "'");
    }

    private void sql(String statement) {
        db.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(20));
    }

    private long count(String query) {
        Object value = db.sql(query).map((row, meta) -> row.get(0)).one().block(Duration.ofSeconds(20));
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        return ((Number) value).longValue();
    }

    private Map<String, Object> one(String query) {
        return db.sql(query).fetch().one().block(Duration.ofSeconds(20));
    }

    private List<Map<String, Object>> rows(String query) {
        return db.sql(query).fetch().all().collectList().block(Duration.ofSeconds(20));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
