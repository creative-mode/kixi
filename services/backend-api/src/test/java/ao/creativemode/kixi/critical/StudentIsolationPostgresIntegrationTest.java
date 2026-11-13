package ao.creativemode.kixi.critical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import ao.creativemode.kixi.identity.service.JwtService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Um aluno não chega ao que é de outro (kixi#453), contra PostgreSQL migrado pelo Flyway. O {@code SecurityConfig}
 * deixa qualquer autenticado fazer GET em simulações, respostas e enunciados: o que protege é o serviço, e este
 * teste percorre cada rota com o recurso do aluno B nas mãos do aluno A. Uma rota nova que herde "autenticado"
 * sem guarda no serviço aparece aqui se for acrescentada à lista.
 *
 * <p>Também prova, em Postgres real, o que um mock não prova: duas submissões simultâneas fecham a simulação uma
 * só vez, e a mesma resposta enviada duas vezes fica numa só linha.</p>
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
class StudentIsolationPostgresIntegrationTest {

    private static final long TEACHER = 123001L;
    private static final long STUDENT_A = 123002L;
    private static final long STUDENT_B = 123003L;
    private static final long ADMIN = 123004L;
    private static final long ENGINE = 123005L;
    private static final long STATEMENT = 123001L;
    private static final long QUESTION = 123001L;
    private static final long OPEN_QUESTION = 123002L;
    private static final long WRONG_OPTION = 123001L;
    private static final long RIGHT_OPTION = 123002L;

    /** Preenchidos no fixture: o id da simulação e da resposta do aluno B. */
    private static final AtomicLong B_SIMULATION = new AtomicLong();
    private static final AtomicLong B_ANSWER = new AtomicLong();

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private DatabaseClient database;
    @Autowired
    private JwtService jwtService;

    private WebTestClient client;
    private String studentA;
    private String studentB;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException(
                    "StudentIsolationPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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
        client = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .responseTimeout(Duration.ofSeconds(20))
                .build();

        sql("DELETE FROM simulation_answers WHERE simulation_id IN "
                + "(SELECT id FROM simulations WHERE statement_id = " + STATEMENT + ")");
        sql("DELETE FROM simulations WHERE statement_id = " + STATEMENT);
        sql("DELETE FROM question_options WHERE question_id = " + QUESTION);
        sql("DELETE FROM questions WHERE id IN (" + QUESTION + ", " + OPEN_QUESTION + ")");
        sql("DELETE FROM statements WHERE id = " + STATEMENT);
        sql("DELETE FROM simulation_audit WHERE actor_account_id IN (" + ADMIN + ", " + ENGINE + ", " + STUDENT_A + ", " + STUDENT_B + ")");
        sql("DELETE FROM account_roles WHERE account_id IN (" + TEACHER + ", " + STUDENT_A + ", " + STUDENT_B + ", " + ADMIN + ", " + ENGINE + ")");
        sql("DELETE FROM accounts WHERE id IN (" + TEACHER + ", " + STUDENT_A + ", " + STUDENT_B + ", " + ADMIN + ", " + ENGINE + ")");
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) VALUES "
                + "(" + TEACHER + ", 'it-iso-teacher', 'it-iso-teacher@example.com', 'x', true),"
                + "(" + STUDENT_A + ", 'it-iso-a', 'it-iso-a@example.com', 'x', true),"
                + "(" + STUDENT_B + ", 'it-iso-b', 'it-iso-b@example.com', 'x', true),"
                + "(" + ADMIN + ", 'it-iso-admin', 'it-iso-admin@example.com', 'x', true),"
                + "(" + ENGINE + ", 'it-iso-engine', 'it-iso-engine@example.com', 'x', true)");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + ENGINE + ", id FROM roles WHERE name = 'INTEGRATION'");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + ADMIN + ", id FROM roles WHERE name = 'ADMIN'");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + TEACHER + ", id FROM roles WHERE name = 'TEACHER'");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + STUDENT_A + ", id FROM roles WHERE name = 'STUDENT'");
        sql("INSERT INTO account_roles (account_id, role_id) SELECT " + STUDENT_B + ", id FROM roles WHERE name = 'STUDENT'");
        sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, "
                + "created_by, visible, needs_review, source) VALUES (" + STATEMENT
                + ", 'Prova de isolamento #453', 'TEST', 60, 5, " + TEACHER + ", true, false, 'manual')");
        sql("INSERT INTO questions (id, statement_id, number, text, question_type, max_score, order_index) VALUES "
                + "(" + QUESTION + ", " + STATEMENT + ", 1, 'Quanto e 2 + 2?', 'multiple_choice', 5, 1),"
                + "(" + OPEN_QUESTION + ", " + STATEMENT + ", 2, 'Explique.', 'open', 5, 2)");
        sql("INSERT INTO question_options (id, question_id, option_label, option_text, is_correct, order_index) VALUES "
                + "(" + WRONG_OPTION + ", " + QUESTION + ", 'A', '3', false, 1),"
                + "(" + RIGHT_OPTION + ", " + QUESTION + ", 'B', '4', true, 2)");

        studentA = jwtService.generateToken(STUDENT_A, List.of("STUDENT"));
        studentB = jwtService.generateToken(STUDENT_B, List.of("STUDENT"));

        B_SIMULATION.set(startSimulation(studentB, STUDENT_B));
        B_ANSWER.set(saveAnswer(studentB, B_SIMULATION.get()));
    }

    /** Método, caminho (com {sim} e {ans}) e corpo de cada rota que o aluno A tenta no que é do B. */
    static Stream<Arguments> routesOfStudentB() {
        String answerBody = "{\"simulationId\":{sim},\"questionId\":" + QUESTION + ",\"selectedOptionId\":" + RIGHT_OPTION + "}";
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/api/v1/simulations/{sim}", null),
                Arguments.of(HttpMethod.GET, "/api/v1/simulations/{sim}/result", null),
                Arguments.of(HttpMethod.PUT, "/api/v1/simulations/{sim}",
                        "{\"accountId\":" + STUDENT_B + ",\"statementId\":" + STATEMENT + "}"),
                Arguments.of(HttpMethod.POST, "/api/v1/simulations/{sim}/submit", null),
                Arguments.of(HttpMethod.POST, "/api/v1/simulations/{sim}/integrity-events", "{\"kind\":\"TAB_HIDDEN\"}"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/simulations/{sim}", null),
                Arguments.of(HttpMethod.DELETE, "/api/v1/simulations/{sim}/permanent", null),
                Arguments.of(HttpMethod.GET, "/api/v1/simulation-answers/{ans}", null),
                Arguments.of(HttpMethod.PUT, "/api/v1/simulation-answers/{ans}", answerBody),
                Arguments.of(HttpMethod.POST, "/api/v1/simulation-answers", answerBody),
                Arguments.of(HttpMethod.PATCH, "/api/v1/simulation-answers/{ans}/grade", "{\"scoreObtained\":5}"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/simulation-answers/{ans}", null));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("routesOfStudentB")
    void studentACannotReadOrChangeWhatBelongsToStudentB(HttpMethod method, String template, String body) {
        String path = template.replace("{sim}", String.valueOf(B_SIMULATION.get()))
                .replace("{ans}", String.valueOf(B_ANSWER.get()));
        WebTestClient.RequestBodySpec request = bearer(studentA).method(method).uri(path)
                .contentType(MediaType.APPLICATION_JSON);

        var result = (body == null ? request.exchange()
                : request.bodyValue(body.replace("{sim}", String.valueOf(B_SIMULATION.get()))).exchange())
                .returnResult(String.class);
        HttpStatusCode status = result.getStatus();

        assertThat(status.value()).as("%s %s", method, path).isIn(401, 403, 404);
        // Nada mudou do lado do B, seja qual for a resposta.
        assertThat(count("SELECT COUNT(*) AS n FROM simulations WHERE id = " + B_SIMULATION.get()
                + " AND status = 'IN_PROGRESS' AND deleted_at IS NULL")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) AS n FROM simulation_answers WHERE id = " + B_ANSWER.get()
                + " AND deleted_at IS NULL")).isEqualTo(1L);
    }

    @Test
    void theListOfStudentAHasNothingOfStudentB() {
        bearer(studentA).get().uri("/api/v1/simulations").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(0);
        bearer(studentA).get().uri("/api/v1/simulation-answers").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void twoSimultaneousSubmissionsCloseTheSimulationOnce() {
        CompletableFuture<HttpStatusCode> first = CompletableFuture.supplyAsync(() -> submit(studentB));
        CompletableFuture<HttpStatusCode> second = CompletableFuture.supplyAsync(() -> submit(studentB));

        List<Integer> statuses = List.of(first.join().value(), second.join().value());

        assertThat(statuses).as("at least one submission wins").contains(200);
        assertThat(statuses).as("none of them is a server error").allMatch(code -> code < 500);
        assertThat(count("SELECT COUNT(*) AS n FROM simulations WHERE id = " + B_SIMULATION.get()
                + " AND status = 'FINISHED'")).isEqualTo(1L);
    }

    @Test
    void theSameAnswerSentTwiceStaysInOneRow() {
        // A segunda gravação é um 409 (ou um 2xx idempotente): o que não pode é criar uma segunda linha.
        bearer(studentB).post().uri("/api/v1/simulation-answers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"simulationId\":" + B_SIMULATION.get() + ",\"questionId\":" + QUESTION
                        + ",\"selectedOptionId\":" + RIGHT_OPTION + "}")
                .exchange().expectStatus().value(code -> assertThat(code).isIn(200, 201, 409));

        assertThat(count("SELECT COUNT(*) AS n FROM simulation_answers WHERE simulation_id = "
                + B_SIMULATION.get() + " AND question_id = " + QUESTION + " AND deleted_at IS NULL")).isEqualTo(1L);
    }

    /** kixi#596: o professor não cria, edita nem apaga a resposta de um aluno; a linha fica como estava. */
    @Test
    void aTeacherCannotCreateEditOrDeleteAStudentsAnswer() {
        // Com o papel STUDENT também, como toda a conta registada: o POST/PUT chega ao controlador e é o dono que decide.
        String teacher = jwtService.generateToken(TEACHER, List.of("TEACHER", "STUDENT"));
        String body = "{\"simulationId\":" + B_SIMULATION.get() + ",\"questionId\":" + QUESTION
                + ",\"selectedOptionId\":" + WRONG_OPTION + "}";

        bearer(teacher).post().uri("/api/v1/simulation-answers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange().expectStatus().isForbidden();
        bearer(teacher).put().uri("/api/v1/simulation-answers/" + B_ANSWER.get()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange().expectStatus().isForbidden();
        bearer(teacher).delete().uri("/api/v1/simulation-answers/" + B_ANSWER.get() + "?reason=sem+permissao")
                .exchange().expectStatus().isForbidden();

        assertThat(count("SELECT COUNT(*) AS n FROM simulation_answers WHERE id = " + B_ANSWER.get()
                + " AND deleted_at IS NULL AND selected_option_id = " + RIGHT_OPTION)).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) AS n FROM simulation_audit WHERE actor_account_id = " + TEACHER)).isEqualTo(0L);
    }

    /**
     * kixi#596: o motor SchoolOps (INTEGRATION), com uma conta diferente da do aluno, abre a simulação e entrega a resposta
     * dele (o IntegrationScope já o previa): é o único caminho em nome de outra conta, e fica no trilho com a conta do
     * motor. Não edita a resposta (o IntegrationScope não o prevê; a nota corrige-se por grade/ai-grade) nem apaga.
     */
    @Test
    void theEngineAccountOpensAndDeliversAStudentsAnswerAndItIsAudited() {
        String engine = jwtService.generateToken(ENGINE, List.of("INTEGRATION"));

        // Abre a simulação da aluna A, com a conta do motor, e entrega a resposta dela.
        AtomicLong simulation = new AtomicLong();
        bearer(engine).post().uri("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + STUDENT_A + ",\"statementId\":" + STATEMENT + "}")
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.id").value(value -> simulation.set(((Number) value).longValue()));
        AtomicLong answer = new AtomicLong();
        bearer(engine).post().uri("/api/v1/simulation-answers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"simulationId\":" + simulation.get() + ",\"questionId\":" + QUESTION
                        + ",\"selectedOptionId\":" + WRONG_OPTION + "}")
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.id").value(value -> answer.set(((Number) value).longValue()));
        // Editar a resposta não faz parte do motor (IntegrationScope): recusado, a resposta fica como estava.
        bearer(engine).put().uri("/api/v1/simulation-answers/" + answer.get()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"simulationId\":" + simulation.get() + ",\"questionId\":" + QUESTION
                        + ",\"selectedOptionId\":" + RIGHT_OPTION + "}")
                .exchange().expectStatus().isForbidden();

        assertThat(count("SELECT COUNT(*) AS n FROM simulation_answers WHERE id = " + answer.get()
                + " AND selected_option_id = " + WRONG_OPTION)).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) AS n FROM simulation_audit WHERE actor_account_id = " + ENGINE
                + " AND ((target_type = 'SIMULATION' AND target_id = " + simulation.get() + " AND action = 'CREATE')"
                + " OR (target_type = 'ANSWER' AND target_id = " + answer.get() + " AND action = 'CREATE'))"))
                .isEqualTo(2L);

        // O motor não apaga nem purga: continua fora do IntegrationScope e do papel de ADMIN.
        bearer(engine).delete().uri("/api/v1/simulation-answers/" + answer.get() + "?reason=nao+deve+poder")
                .exchange().expectStatus().isForbidden();
        bearer(engine).delete().uri("/api/v1/simulations/" + simulation.get() + "?reason=nao+deve+poder")
                .exchange().expectStatus().isForbidden();
    }

    /**
     * O contrato com o motor SchoolOps (schoolops#20): a sequência exata com que o motor entrega uma prova em papel, pela
     * conta INTEGRATION. Se uma mudança de segurança partir um destes passos, o motor deixa de submeter provas.
     */
    @Test
    void theEngineAccountRunsTheWholePaperExamSequenceOfSchoolOps() {
        String engine = jwtService.generateToken(ENGINE, List.of("INTEGRATION"));
        String open = "{\"accountId\":" + STUDENT_A + ",\"statementId\":" + STATEMENT + ",\"status\":\"IN_PROGRESS\"}";

        // 1 e 2: abre e cancela a simulação que o aluno possa ter (o motor recomeça sempre limpo).
        AtomicLong first = new AtomicLong();
        bearer(engine).post().uri("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON).bodyValue(open)
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.id").value(value -> first.set(((Number) value).longValue()));
        bearer(engine).put().uri("/api/v1/simulations/" + first.get()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + STUDENT_A + ",\"statementId\":" + STATEMENT + ",\"status\":\"CANCELLED\"}")
                .exchange().expectStatus().isOk();

        // 3: a simulação a sério, e as respostas lidas da folha (objetiva e aberta).
        AtomicLong simulation = new AtomicLong();
        bearer(engine).post().uri("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON).bodyValue(open)
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.id").value(value -> simulation.set(((Number) value).longValue()));
        bearer(engine).post().uri("/api/v1/simulation-answers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"simulationId\":" + simulation.get() + ",\"questionId\":" + QUESTION
                        + ",\"selectedOptionId\":" + RIGHT_OPTION + "}").exchange().expectStatus().isCreated();
        AtomicLong openAnswer = new AtomicLong();
        bearer(engine).post().uri("/api/v1/simulation-answers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"simulationId\":" + simulation.get() + ",\"questionId\":" + OPEN_QUESTION
                        + ",\"answerText\":\"resposta lida por OCR\"}")
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.id").value(value -> openAnswer.set(((Number) value).longValue()));

        // 4: fecha a submissão e lê o que o Kixi corrigiu.
        bearer(engine).post().uri("/api/v1/simulations/" + simulation.get() + "/submit").exchange().expectStatus().isOk();
        bearer(engine).get().uri("/api/v1/simulations/" + simulation.get() + "/result").exchange().expectStatus().isOk();
        bearer(engine).get().uri("/api/v1/simulations").exchange().expectStatus().isOk();
        bearer(engine).get().uri("/api/v1/simulation-answers").exchange().expectStatus().isOk();

        // 5: a nota da aberta, e a sua retificação por reclamação.
        bearer(engine).patch().uri("/api/v1/simulation-answers/" + openAnswer.get() + "/grade").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"scoreObtained\":3}").exchange().expectStatus().isOk();
        bearer(engine).patch().uri("/api/v1/simulation-answers/" + openAnswer.get() + "/grade?rectify=true").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"scoreObtained\":4}").exchange().expectStatus().isOk();

        // Tudo o que escreveu em nome do aluno ficou no trilho com a conta do motor.
        assertThat(count("SELECT COUNT(*) AS n FROM simulation_audit WHERE actor_account_id = " + ENGINE)).isGreaterThanOrEqualTo(4L);
    }

    /** kixi#596: o aluno que responde à sua própria prova também fica no trilho (criar e editar). */
    @Test
    void aStudentWritingOwnAnswerIsAudited() {
        // A resposta do fixture foi criada pelo aluno B.
        assertThat(count("SELECT COUNT(*) AS n FROM simulation_audit WHERE target_type = 'ANSWER' AND target_id = "
                + B_ANSWER.get() + " AND action = 'CREATE' AND actor_account_id = " + STUDENT_B)).isEqualTo(1L);

        bearer(studentB).put().uri("/api/v1/simulation-answers/" + B_ANSWER.get()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"simulationId\":" + B_SIMULATION.get() + ",\"questionId\":" + QUESTION
                        + ",\"selectedOptionId\":" + WRONG_OPTION + "}")
                .exchange().expectStatus().isOk();
        assertThat(count("SELECT COUNT(*) AS n FROM simulation_audit WHERE target_type = 'ANSWER' AND target_id = "
                + B_ANSWER.get() + " AND action = 'UPDATE' AND actor_account_id = " + STUDENT_B)).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) AS n FROM simulation_audit WHERE target_type = 'SIMULATION' AND target_id = "
                + B_SIMULATION.get() + " AND action = 'CREATE' AND actor_account_id = " + STUDENT_B)).isEqualTo(1L);
    }

    /**
     * kixi#596 (o mesmo problema nas simulações): o staff não abre nem edita a simulação de um aluno, o professor não apaga,
     * e o administrador apaga, restaura e purga com motivo, confirmação e trilho.
     */
    @Test
    void simulationsFollowTheSameRules() {
        String teacher = jwtService.generateToken(TEACHER, List.of("TEACHER", "STUDENT"));
        String admin = jwtService.generateToken(ADMIN, List.of("ADMIN", "STUDENT"));
        String onBehalf = "{\"accountId\":" + STUDENT_A + ",\"statementId\":" + STATEMENT + "}";

        // Nem o professor nem o administrador abrem a simulação de outra conta (só a sua).
        for (String staff : List.of(teacher, admin)) {
            bearer(staff).post().uri("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(onBehalf).exchange().expectStatus().isForbidden();
            bearer(staff).put().uri("/api/v1/simulations/" + B_SIMULATION.get()).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"accountId\":" + STUDENT_B + ",\"statementId\":" + STATEMENT + "}")
                    .exchange().expectStatus().is4xxClientError();
        }
        // O professor não apaga nem restaura.
        bearer(teacher).delete().uri("/api/v1/simulations/" + B_SIMULATION.get() + "?reason=sem+permissao")
                .exchange().expectStatus().isForbidden();
        bearer(teacher).put().uri("/api/v1/simulations/" + B_SIMULATION.get() + "/restore?reason=sem+permissao")
                .exchange().expectStatus().isForbidden();
        assertThat(count("SELECT COUNT(*) AS n FROM simulations WHERE id = " + B_SIMULATION.get()
                + " AND deleted_at IS NULL")).isEqualTo(1L);

        // Uma simulação da aluna A, sem respostas, para o administrador apagar e purgar.
        long mine = startSimulation(jwtService.generateToken(STUDENT_A, List.of("STUDENT")), STUDENT_A);
        String uri = "/api/v1/simulations/" + mine;
        bearer(admin).delete().uri(uri).exchange().expectStatus().isBadRequest();
        bearer(admin).delete().uri(uri + "?reason=abc").exchange().expectStatus().isBadRequest();
        bearer(admin).delete().uri(uri + "?reason=simulacao+de+teste").exchange().expectStatus().isNoContent();
        bearer(admin).put().uri(uri + "/restore?reason=apagada+por+engano").exchange().expectStatus().isNoContent();
        bearer(admin).delete().uri(uri + "?reason=simulacao+de+teste").exchange().expectStatus().isNoContent();
        // O purge pede a confirmação com o id, além de a simulação já estar na lixeira.
        bearer(admin).delete().uri(uri + "/permanent?reason=pedido+do+aluno").exchange().expectStatus().isBadRequest();
        bearer(admin).delete().uri(uri + "/permanent?reason=pedido+do+aluno&confirm=" + mine)
                .exchange().expectStatus().isNoContent();
        assertThat(count("SELECT COUNT(*) AS n FROM simulations WHERE id = " + mine)).isEqualTo(0L);

        assertThat(count("SELECT COUNT(*) AS n FROM simulation_audit WHERE target_type = 'SIMULATION' AND target_id = "
                + mine + " AND actor_account_id = " + ADMIN
                + " AND action IN ('SOFT_DELETE', 'RESTORE', 'PURGE') AND reason IS NOT NULL")).isEqualTo(4L);
    }

    /**
     * kixi#596: a mutação e o registo da auditoria são uma só transação. Com o INSERT do trilho a falhar (a tabela
     * desaparece por um instante), o pedido devolve erro E a resposta continua intacta: nunca fica apagada sem registo.
     */
    @Test
    void ifTheAuditInsertFailsTheDeleteIsRolledBack() {
        String admin = jwtService.generateToken(ADMIN, List.of("ADMIN"));
        String uri = "/api/v1/simulation-answers/" + B_ANSWER.get();

        sql("ALTER TABLE simulation_audit RENAME TO simulation_audit_off");
        try {
            bearer(admin).delete().uri(uri + "?reason=resposta+duplicada").exchange()
                    .expectStatus().is5xxServerError();
            assertThat(count("SELECT COUNT(*) AS n FROM simulation_answers WHERE id = " + B_ANSWER.get()
                    + " AND deleted_at IS NULL")).as("a resposta não ficou apagada").isEqualTo(1L);
        } finally {
            sql("ALTER TABLE simulation_audit_off RENAME TO simulation_audit");
        }
        assertThat(count("SELECT COUNT(*) AS n FROM simulation_audit WHERE target_type = 'ANSWER' AND target_id = " + B_ANSWER.get()
                + " AND action = 'SOFT_DELETE'")).isEqualTo(0L);

        // Com o trilho de volta, o mesmo pedido apaga e regista.
        bearer(admin).delete().uri(uri + "?reason=resposta+duplicada").exchange().expectStatus().isNoContent();
        assertThat(count("SELECT COUNT(*) AS n FROM simulation_audit WHERE target_type = 'ANSWER' AND target_id = " + B_ANSWER.get()
                + " AND action = 'SOFT_DELETE'")).isEqualTo(1L);
    }

    /**
     * kixi#596: o purge irreversível tem duas etapas. Só se purga o que já foi apagado (com motivo e trilho): purgar uma
     * resposta ativa dá 400 e não toca em nada, nem no trilho.
     */
    @Test
    void purgeOnlyWorksAfterASeparateAuditedSoftDelete() {
        String admin = jwtService.generateToken(ADMIN, List.of("ADMIN"));
        String uri = "/api/v1/simulation-answers/" + B_ANSWER.get();

        bearer(admin).delete().uri(uri + "/purge?reason=pedido+do+aluno&confirm=" + B_ANSWER.get()).exchange().expectStatus().isBadRequest();
        assertThat(count("SELECT COUNT(*) AS n FROM simulation_answers WHERE id = " + B_ANSWER.get()
                + " AND deleted_at IS NULL")).as("a resposta ativa não se purga").isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) AS n FROM simulation_audit WHERE target_type = 'ANSWER' AND target_id = " + B_ANSWER.get()
                + " AND action IN ('SOFT_DELETE', 'PURGE')")).as("nada a registar numa tentativa recusada").isEqualTo(0L);

        bearer(admin).delete().uri(uri + "?reason=resposta+duplicada").exchange().expectStatus().isNoContent();
        bearer(admin).delete().uri(uri + "/purge?reason=pedido+do+aluno&confirm=" + B_ANSWER.get()).exchange().expectStatus().isNoContent();
        assertThat(count("SELECT COUNT(*) AS n FROM simulation_answers WHERE id = " + B_ANSWER.get())).isEqualTo(0L);
    }

    /** kixi#596: o administrador apaga e purga com motivo, e cada acto fica no trilho, que sobrevive ao purge. */
    @Test
    void anAdminDeletesAndPurgesWithAReasonAndLeavesAnAuditTrail() {
        String admin = jwtService.generateToken(ADMIN, List.of("ADMIN"));
        String uri = "/api/v1/simulation-answers/" + B_ANSWER.get();

        bearer(admin).delete().uri(uri).exchange().expectStatus().isBadRequest();
        bearer(admin).delete().uri(uri + "?reason=abc").exchange().expectStatus().isBadRequest();
        assertThat(count("SELECT COUNT(*) AS n FROM simulation_answers WHERE id = " + B_ANSWER.get()
                + " AND deleted_at IS NULL")).isEqualTo(1L);

        bearer(admin).delete().uri(uri + "?reason=resposta+duplicada").exchange().expectStatus().isNoContent();
        assertThat(count("SELECT COUNT(*) AS n FROM simulation_answers WHERE id = " + B_ANSWER.get()
                + " AND deleted_at IS NOT NULL")).isEqualTo(1L);

        bearer(admin).delete().uri(uri + "/purge?reason=pedido+do+aluno&confirm=" + B_ANSWER.get()).exchange().expectStatus().isNoContent();
        assertThat(count("SELECT COUNT(*) AS n FROM simulation_answers WHERE id = " + B_ANSWER.get())).isEqualTo(0L);

        assertThat(count("SELECT COUNT(*) AS n FROM simulation_audit WHERE target_type = 'ANSWER' AND target_id = " + B_ANSWER.get()
                + " AND actor_account_id = " + ADMIN + " AND simulation_id = " + B_SIMULATION.get()
                + " AND ((action = 'SOFT_DELETE' AND reason = 'resposta duplicada')"
                + " OR (action = 'PURGE' AND reason = 'pedido do aluno'))")).isEqualTo(2L);
    }

    private HttpStatusCode submit(String token) {
        return bearer(token).post().uri("/api/v1/simulations/" + B_SIMULATION.get() + "/submit")
                .exchange().returnResult(String.class).getStatus();
    }

    private long startSimulation(String token, long account) {
        AtomicLong id = new AtomicLong();
        bearer(token).post().uri("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"accountId\":" + account + ",\"statementId\":" + STATEMENT + "}")
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.id").value(value -> id.set(((Number) value).longValue()));
        return id.get();
    }

    private long saveAnswer(String token, long simulation) {
        AtomicLong id = new AtomicLong();
        bearer(token).post().uri("/api/v1/simulation-answers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"simulationId\":" + simulation + ",\"questionId\":" + QUESTION
                        + ",\"selectedOptionId\":" + RIGHT_OPTION + "}")
                .exchange().expectStatus().is2xxSuccessful()
                .expectBody().jsonPath("$.id").value(value -> id.set(((Number) value).longValue()));
        return id.get();
    }

    private Long count(String query) {
        return database.sql(query).map((row, metadata) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(20));
    }

    private WebTestClient bearer(String token) {
        return client.mutate().defaultHeaders(headers -> headers.setBearerAuth(token)).build();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
