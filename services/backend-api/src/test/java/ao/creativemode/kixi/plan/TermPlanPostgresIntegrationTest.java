package ao.creativemode.kixi.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import ao.creativemode.kixi.identity.service.JwtService;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

/** O plano do trimestre, o coordenador por instituição e quem vê o quê (kixi#523, kixi#513), contra PostgreSQL. */
@SpringBootTest(properties = {
        "spring.r2dbc.pool.max-size=2",
        "spring.r2dbc.pool.initial-size=0",
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
class TermPlanPostgresIntegrationTest {

    private static final long ADMIN = 523001L;
    private static final long TEACHER = 523002L;        // leciona a turma 523001 + disciplina 523001
    private static final long OTHER_TEACHER = 523003L;  // leciona a turma 523001 mas outra disciplina
    private static final long COORDINATOR = 523004L;
    private static final long STUDENT = 523005L;
    private static final long CLASS = 523001L;
    private static final long SUBJECT = 523001L;
    private static final long INSTITUTION = 523001L;
    private static final String ACCOUNTS = "523001, 523002, 523003, 523004, 523005";

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private JwtService jwtService;

    private WebTestClient client;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("TermPlanPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
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
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) SELECT i, 'it-523-' || i, 'it-523-' || i || '@example.com', 'x', true "
                + "FROM generate_series(523001, 523005) i");
        role(ADMIN, "ADMIN");
        role(TEACHER, "TEACHER");
        role(OTHER_TEACHER, "TEACHER");
        role(COORDINATOR, "COORDINATOR");
        role(STUDENT, "STUDENT");
        sql("INSERT INTO institutions (id, code, name, created_at, updated_at) VALUES (" + INSTITUTION + ", 'IT523', 'Escola 523', now(), now())");
        sql("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at) VALUES (523001, 2191, 2192, now(), now())");
        sql("INSERT INTO courses (id, code, name, institution_id, created_at, updated_at) VALUES (523001, 'C523', 'Informática', " + INSTITUTION + ", now(), now())");
        sql("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id, created_at, updated_at) VALUES "
                + "(" + CLASS + ", 'A1', 12, 523001, 523001, " + INSTITUTION + ", now(), now())");
        sql("INSERT INTO subjects (id, code, name, created_at, updated_at) VALUES (523001, 'M523', 'Matemática', now(), now()), (523002, 'I523', 'Inglês', now(), now())");
        sql("INSERT INTO teachers (id, account_id, first_name, last_name, created_at, updated_at) VALUES "
                + "(523002, 523002, 'Abner', 'Teste', now(), now()), (523003, 523003, 'Andre', 'Teste', now(), now())");
        sql("INSERT INTO teaching_assignments (teacher_id, class_id, subject_id, school_year_id, created_at) VALUES "
                + "(523002, " + CLASS + ", 523001, 523001, now()), (523003, " + CLASS + ", 523002, 523001, now())");
    }

    @AfterEach
    void clean() {
        sql("DELETE FROM term_assessments WHERE plan_id IN (SELECT id FROM term_plans WHERE class_id = " + CLASS + ")");
        sql("DELETE FROM term_plans WHERE class_id = " + CLASS);
        sql("DELETE FROM simulation_answers WHERE simulation_id IN (SELECT id FROM simulations WHERE statement_id IN (523001))");
        sql("DELETE FROM simulations WHERE statement_id = 523001");
        sql("DELETE FROM exam_rooms WHERE statement_id = 523001");
        sql("DELETE FROM statements WHERE id = 523001");
        sql("DELETE FROM institution_coordinators WHERE institution_id = " + INSTITUTION);
        sql("DELETE FROM teaching_assignments WHERE teacher_id IN (523002, 523003)");
        sql("DELETE FROM teachers WHERE id IN (523002, 523003)");
        sql("DELETE FROM classes WHERE id = " + CLASS);
        sql("DELETE FROM courses WHERE id = 523001");
        sql("DELETE FROM subjects WHERE id IN (523001, 523002)");
        sql("DELETE FROM school_years WHERE id = 523001");
        sql("DELETE FROM institutions WHERE id = " + INSTITUTION);
        sql("DELETE FROM account_roles WHERE account_id IN (" + ACCOUNTS + ")");
        sql("DELETE FROM accounts WHERE id IN (" + ACCOUNTS + ")");
    }

    private String body(int mac) {
        LocalDate d = LocalDate.now();
        StringBuilder dates = new StringBuilder();
        for (int i = 0; i < mac; i++) dates.append(i == 0 ? "" : ",").append('"').append(d.plusDays(10 + i)).append('"');
        return "{\"classId\":" + CLASS + ",\"subjectId\":" + SUBJECT + ",\"termId\":1,\"p1Date\":\"" + d.plusDays(1) + "\",\"p2Date\":\"" + d.plusDays(30)
                + "\",\"macCount\":" + mac + ",\"macDates\":[" + dates + "]}";
    }

    @Test
    void theTeacherPlansTheTermAndTheStatusFollowsTheStatementTheRoomAndTheGrading() {
        String teacher = token(TEACHER, "TEACHER");
        AtomicLong plan = new AtomicLong();
        bearer(teacher).post().uri("/api/v1/term-plans").contentType(MediaType.APPLICATION_JSON).bodyValue(body(2)).exchange()
                .expectStatus().isCreated().expectBody()
                .jsonPath("$.assessments.length()").isEqualTo(4)
                .jsonPath("$.assessments[0].kind").isEqualTo("P1")
                .jsonPath("$.assessments[1].kind").isEqualTo("P2")
                .jsonPath("$.assessments[2].title").isEqualTo("MAC 1")
                .jsonPath("$.assessments[0].status").isEqualTo("PLANNED")
                .jsonPath("$.readyForPauta").isEqualTo(false)
                .jsonPath("$.className").isEqualTo("A1")
                .jsonPath("$.id").value(v -> plan.set(((Number) v).longValue()));
        // o mesmo plano duas vezes: conflito
        bearer(teacher).post().uri("/api/v1/term-plans").contentType(MediaType.APPLICATION_JSON).bodyValue(body(2)).exchange().expectStatus().isEqualTo(409);

        long p1 = assessmentId(plan.get(), "P1", 1);
        sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, created_by, visible, needs_review, source, class_id, subject_id) VALUES "
                + "(523001, 'P1 Matemática', 'TEST', 60, 5, " + TEACHER + ", false, false, 'manual', " + CLASS + ", " + SUBJECT + ")");
        patch(teacher, plan.get(), p1, "{\"statementId\":523001}").jsonPath("$.assessments[0].status").isEqualTo("STATEMENT_READY");
        // concluir o agendamento pede P1 e P2 com enunciado; só o professor da turma (ou o coordenador) conclui
        bearer(teacher).post().uri("/api/v1/term-plans/" + plan.get() + "/conclude").exchange().expectStatus().isEqualTo(409);
        long p2Early = assessmentId(plan.get(), "P2", 1);
        sql("UPDATE term_assessments SET statement_id = 523001 WHERE id = " + p2Early);
        bearer(token(OTHER_TEACHER, "TEACHER")).post().uri("/api/v1/term-plans/" + plan.get() + "/conclude").exchange().expectStatus().isForbidden();
        bearer(teacher).post().uri("/api/v1/term-plans/" + plan.get() + "/conclude").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.schedulingConcluded").isEqualTo(true);
        sql("UPDATE term_assessments SET statement_id = NULL WHERE id = " + p2Early);
        sql("UPDATE term_plans SET scheduling_concluded_at = NULL WHERE id = " + plan.get());
        sql("INSERT INTO exam_rooms (id, statement_id, teacher_account_id, starts_at, ends_at, duration_minutes, status, class_id) VALUES "
                + "(523001, 523001, " + TEACHER + ", now(), now() + interval '1 day', 60, 'RUNNING', " + CLASS + ")");
        patch(teacher, plan.get(), p1, "{\"roomId\":523001}").jsonPath("$.assessments[0].status").isEqualTo("ROOM_OPEN");
        // ligar a sala traz o enunciado dela (o motor precisa dele para abrir a pauta)
        assertThat(count("SELECT statement_id FROM term_assessments WHERE id = " + p1)).isEqualTo(523001L);

        sql("INSERT INTO simulations (id, account_id, statement_id, status, exam_room_id) VALUES (523001, " + STUDENT + ", 523001, 'FINISHED', 523001)");
        sql("INSERT INTO questions (id, statement_id, number, text, question_type, max_score, order_index) VALUES (523001, 523001, 1, 'q', 'open', 5, 1)");
        sql("INSERT INTO simulation_answers (simulation_id, question_id, answer_text, review_status) VALUES (523001, 523001, 'x', 'PENDING_REVIEW')");
        sql("UPDATE exam_rooms SET status = 'CLOSED' WHERE id = 523001");
        bearer(teacher).get().uri("/api/v1/term-plans/" + plan.get()).exchange().expectBody().jsonPath("$.assessments[0].status").isEqualTo("DONE");
        sql("UPDATE simulation_answers SET review_status = 'GRADED' WHERE simulation_id = 523001");
        bearer(teacher).get().uri("/api/v1/term-plans/" + plan.get()).exchange().expectBody().jsonPath("$.assessments[0].status").isEqualTo("GRADED");
        sql("DELETE FROM simulation_answers WHERE simulation_id = 523001");
        sql("DELETE FROM questions WHERE id = 523001");

        // MAC sem sala: o professor marca-a concluída; só o coordenador dispensa
        long mac1 = assessmentId(plan.get(), "MAC", 1);
        long mac2 = assessmentId(plan.get(), "MAC", 2);
        long p2 = assessmentId(plan.get(), "P2", 1);
        bearer(teacher).post().uri("/api/v1/term-plans/" + plan.get() + "/assessments/" + mac1 + "/done").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.assessments[2].status").isEqualTo("GRADED");
        bearer(teacher).post().uri("/api/v1/term-plans/" + plan.get() + "/assessments/" + p2 + "/reschedule").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"date\":\"2030-01-10\",\"reason\":\"prova anulada\"}").exchange().expectStatus().isForbidden();
        String coordinator = token(COORDINATOR, "COORDINATOR");
        bearer(token(ADMIN, "ADMIN")).put().uri("/api/v1/institutions/" + INSTITUTION + "/coordinators/" + COORDINATOR).exchange().expectStatus().isNoContent();
        // a P1 e a P2 não se dispensam: repetem-se
        bearer(coordinator).post().uri("/api/v1/term-plans/" + plan.get() + "/assessments/" + p2 + "/dispense").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"prova anulada\"}").exchange().expectStatus().isEqualTo(409);
        bearer(coordinator).post().uri("/api/v1/term-plans/" + plan.get() + "/assessments/" + p2 + "/reschedule").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"date\":\"2030-01-10\",\"reason\":\" \"}").exchange().expectStatus().isBadRequest();
        bearer(coordinator).post().uri("/api/v1/term-plans/" + plan.get() + "/assessments/" + mac1 + "/reschedule").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"date\":\"2030-01-10\",\"reason\":\"x\"}").exchange().expectStatus().isBadRequest();   // a MAC não se reagenda
        // reagendar a P1 (já corrigida) volta a planeá-la, sem enunciado nem sala, e o plano deixa de estar pronto
        bearer(coordinator).post().uri("/api/v1/term-plans/" + plan.get() + "/assessments/" + p1 + "/reschedule").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"date\":\"2030-01-10\",\"reason\":\"prova anulada\"}").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.assessments[0].status").isEqualTo("PLANNED").jsonPath("$.assessments[0].plannedDate").isEqualTo("2030-01-10")
                .jsonPath("$.assessments[0].roomId").isEqualTo(null).jsonPath("$.readyForPauta").isEqualTo(false);
        // a prova repetida volta a ligar-se e a corrigir-se: o plano espera por ela e só então fica pronto
        sql("UPDATE term_assessments SET manual_done_at = now() WHERE id IN (" + p1 + ", " + p2 + ")");
        bearer(coordinator).post().uri("/api/v1/term-plans/" + plan.get() + "/assessments/" + mac2 + "/dispense").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"MAC cancelada\"}").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.readyForPauta").isEqualTo(true);   // P1 e P2 repetidas e corrigidas, MAC 1 concluída, MAC 2 dispensada
        bearer(coordinator).post().uri("/api/v1/term-plans/" + plan.get() + "/approve").exchange().expectStatus().isOk().expectBody().jsonPath("$.approved").isEqualTo(true);
        // o professor não aprova
        bearer(teacher).post().uri("/api/v1/term-plans/" + plan.get() + "/approve").exchange().expectStatus().isForbidden();
    }

    @Test
    void remindersFollowTheDatesAndOnlyTheOwnerSeesThem() {
        String teacher = token(TEACHER, "TEACHER");
        LocalDate d = LocalDate.now();
        String late = "{\"classId\":" + CLASS + ",\"subjectId\":" + SUBJECT + ",\"termId\":1,\"p1Date\":\"" + d.minusDays(2) + "\",\"p2Date\":\"" + d.plusDays(6)
                + "\",\"macCount\":0,\"macDates\":[]}";
        bearer(teacher).post().uri("/api/v1/term-plans").contentType(MediaType.APPLICATION_JSON).bodyValue(late).exchange().expectStatus().isCreated();
        bearer(teacher).get().uri("/api/v1/term-plans/reminders").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[?(@.kind=='P1')].level").isEqualTo("LATE")
                .jsonPath("$[?(@.kind=='P1')].action").isEqualTo("BUILD_STATEMENT")
                .jsonPath("$[?(@.kind=='P2')].level").isEqualTo("D7");
        bearer(token(OTHER_TEACHER, "TEACHER")).get().uri("/api/v1/term-plans/reminders").exchange().expectBody().jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void accessFollowsTheTeacherTheCoordinatorAndTheAdministrator() {
        AtomicLong plan = new AtomicLong();
        bearer(token(TEACHER, "TEACHER")).post().uri("/api/v1/term-plans").contentType(MediaType.APPLICATION_JSON).bodyValue(body(1)).exchange()
                .expectStatus().isCreated().expectBody().jsonPath("$.id").value(v -> plan.set(((Number) v).longValue()));
        String uri = "/api/v1/term-plans/" + plan.get();
        // outro professor da turma, mas de outra disciplina: nem lê nem planeia nem edita
        String other = token(OTHER_TEACHER, "TEACHER");
        bearer(other).get().uri(uri).exchange().expectStatus().isForbidden();
        bearer(other).put().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue("{\"p1Date\":\"" + LocalDate.now().plusDays(3) + "\"}").exchange().expectStatus().isForbidden();
        bearer(other).post().uri("/api/v1/term-plans").contentType(MediaType.APPLICATION_JSON).bodyValue(body(1)).exchange().expectStatus().isForbidden();
        // aluno e integração: nada
        bearer(token(STUDENT, "STUDENT")).get().uri(uri).exchange().expectStatus().isForbidden();
        bearer(token(STUDENT, "STUDENT")).get().uri("/api/v1/term-plans").exchange().expectStatus().isForbidden();
        bearer(token(OTHER_TEACHER, "INTEGRATION")).get().uri(uri).exchange().expectStatus().is4xxClientError(); // fora do âmbito da integração
        // coordenador sem instituição atribuída: nada; com ela, lê mas não edita
        String coordinator = token(COORDINATOR, "COORDINATOR");
        bearer(coordinator).get().uri(uri).exchange().expectStatus().isForbidden();
        bearer(token(ADMIN, "ADMIN")).put().uri("/api/v1/institutions/" + INSTITUTION + "/coordinators/" + COORDINATOR).exchange().expectStatus().isNoContent();
        bearer(coordinator).get().uri(uri).exchange().expectStatus().isOk();
        bearer(coordinator).put().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue("{\"p1Date\":\"" + LocalDate.now().plusDays(3) + "\"}").exchange().expectStatus().isForbidden();
        // só o administrador atribui coordenadores
        bearer(token(TEACHER, "TEACHER")).put().uri("/api/v1/institutions/" + INSTITUTION + "/coordinators/" + OTHER_TEACHER).exchange().expectStatus().isForbidden();
        bearer(coordinator).put().uri("/api/v1/institutions/" + INSTITUTION + "/coordinators/" + OTHER_TEACHER).exchange().expectStatus().isForbidden();
        bearer(token(ADMIN, "ADMIN")).get().uri("/api/v1/institutions/" + INSTITUTION + "/coordinators").exchange().expectBody().jsonPath("$.length()").isEqualTo(1);
        // o professor altera a data e acrescenta uma MAC; o administrador retira-a
        bearer(token(TEACHER, "TEACHER")).put().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"macCount\":2,\"macDates\":[\"" + LocalDate.now().plusDays(11) + "\",\"" + LocalDate.now().plusDays(12) + "\"]}").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.assessments.length()").isEqualTo(4);
        bearer(token(ADMIN, "ADMIN")).put().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue("{\"macCount\":1}").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.assessments.length()").isEqualTo(3);
        // retirar o coordenador tira-lhe o papel se não coordenar mais nenhuma instituição
        bearer(token(ADMIN, "ADMIN")).delete().uri("/api/v1/institutions/" + INSTITUTION + "/coordinators/" + COORDINATOR).exchange().expectStatus().isNoContent();
        assertThat(count("SELECT count(*) FROM account_roles ar JOIN roles r ON r.id = ar.role_id WHERE ar.account_id = " + COORDINATOR + " AND r.name = 'COORDINATOR'")).isZero();
    }

    @Test
    void theCoordinatorFollowsTheRoomsOfTheirInstitutionReadOnly() {
        sql("INSERT INTO statements (id, title, exam_type, duration_minutes, total_max_score, created_by, visible, needs_review, source, class_id, subject_id) VALUES "
                + "(523001, 'P1 Matemática', 'TEST', 60, 5, " + TEACHER + ", false, false, 'manual', " + CLASS + ", " + SUBJECT + ")");
        sql("INSERT INTO exam_rooms (id, statement_id, teacher_account_id, starts_at, ends_at, duration_minutes, status, class_id) VALUES "
                + "(523001, 523001, " + TEACHER + ", now(), now() + interval '1 day', 60, 'RUNNING', " + CLASS + ")");
        sql("INSERT INTO simulations (id, account_id, statement_id, status, exam_room_id, final_score) VALUES (523001, " + STUDENT + ", 523001, 'FINISHED', 523001, 4.5)");
        sql("INSERT INTO exam_room_participants (exam_room_id, account_id, simulation_id, joined_at) VALUES (523001, " + STUDENT + ", 523001, CURRENT_TIMESTAMP)");
        String coordinator = token(COORDINATOR, "COORDINATOR");
        // sem instituição atribuída: nada
        bearer(coordinator).get().uri("/api/v1/coordination/exam-rooms").exchange().expectStatus().isForbidden();
        bearer(token(ADMIN, "ADMIN")).put().uri("/api/v1/institutions/" + INSTITUTION + "/coordinators/" + COORDINATOR).exchange().expectStatus().isNoContent();
        bearer(coordinator).get().uri("/api/v1/coordination/exam-rooms").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$[?(@.id==523001)].className").isEqualTo("A1")
                .jsonPath("$[?(@.id==523001)].participants").isEqualTo(1)
                .jsonPath("$[?(@.id==523001)].finished").isEqualTo(1)
                .jsonPath("$[?(@.id==523001)].status").isEqualTo("RUNNING");
        bearer(coordinator).get().uri("/api/v1/coordination/exam-rooms/523001/grades").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$[0].finalScore").isEqualTo(4.5).jsonPath("$[0].status").isEqualTo("FINISHED");
        // uma sala que não existe dá o mesmo que a de outra instituição: 403
        bearer(coordinator).get().uri("/api/v1/coordination/exam-rooms/999999999/grades").exchange().expectStatus().isForbidden();
        // só leitura: nada de escrita, e o professor e o aluno não acompanham a coordenação
        bearer(coordinator).post().uri("/api/v1/coordination/exam-rooms").exchange().expectStatus().isForbidden();
        bearer(token(TEACHER, "TEACHER")).get().uri("/api/v1/coordination/exam-rooms").exchange().expectStatus().isForbidden();
        bearer(token(STUDENT, "STUDENT")).get().uri("/api/v1/coordination/exam-rooms/523001/grades").exchange().expectStatus().isForbidden();
        bearer(token(ADMIN, "ADMIN")).get().uri("/api/v1/coordination/exam-rooms").exchange().expectStatus().isOk();
        sql("DELETE FROM exam_room_participants WHERE exam_room_id = 523001");
        sql("DELETE FROM simulations WHERE id = 523001");
    }

    private long assessmentId(long plan, String kind, int seq) {
        return count("SELECT id FROM term_assessments WHERE plan_id = " + plan + " AND kind = '" + kind + "' AND seq = " + seq);
    }

    private WebTestClient.BodyContentSpec patch(String token, long plan, long assessment, String json) {
        return bearer(token).patch().uri("/api/v1/term-plans/" + plan + "/assessments/" + assessment).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json).exchange().expectStatus().isOk().expectBody();
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
