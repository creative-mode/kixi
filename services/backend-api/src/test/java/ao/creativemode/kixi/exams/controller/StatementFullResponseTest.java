package ao.creativemode.kixi.exams.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.service.ManualStatementService;
import ao.creativemode.kixi.exams.service.StatementService;
import ao.creativemode.kixi.exams.service.StatementWithQuestions;
import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClientConfigurer;
import reactor.core.publisher.Mono;

/**
 * {@code GET /api/v1/statements/{id}/full}, and who gets the answer key.
 *
 * <p>This route had no test at all until #104, and that is how it came to leak.
 * The OCR writes {@code is_correct = false} on every option and nothing could set
 * otherwise, so the flag this route returned was the same on every paper and
 * harmless to hand a student. #104 gave teachers a way to mark the real one,
 * which turned an always-false field into the answer — while the routes written
 * alongside it withheld it carefully.
 *
 * <p>Both halves are pinned here: the student does not get the flag, and staff
 * still do. Without the second, a fix that simply dropped the field everywhere
 * would pass.
 */
@WebFluxTest(controllers = StatementController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class StatementFullResponseTest {

    private static final Long TEACHER_ID = 7L;
    private static final Long STUDENT_ID = 12L;
    private static final Long STATEMENT_ID = 10L;
    private static final Long QUESTION_ID = 20L;
    private static final Long OPTION_ID = 30L;

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private StatementService statementService;

    @MockBean
    private ManualStatementService manualStatementService;

    private Statement statement;

    @BeforeEach
    void setUp() {
        statement = new Statement("P1", "Prova de Matematica");
        statement.setId(STATEMENT_ID);
        statement.setSource("ocr");
        statement.setVisible(true);
        statement.setNeedsReview(true);
    }

    // ── The answer key ──────────────────────────────────────────────────────

    @Test
    void aStudentGetsTheAlternativesWithoutTheAnswer() {
        givenAFullStatement();

        String json = bodyAsJson(studentJwt());

        // The flag has to be absent, not null: doesNotExist() on a JsonPath also
        // accepts an explicit null, so only the raw body can tell.
        assertThat(json).doesNotContain("isCorrect");
        assertThat(json).doesNotContain("needsReview");
        // And the paper itself is still readable, or the route is useless.
        assertThat(json).contains("Resolva x+1=2").contains("x = 1");
    }

    @Test
    void staffGetTheAnswerAndTheReviewState() {
        givenAFullStatement();

        String json = bodyAsJson(teacherJwt());

        assertThat(json).contains("\"isCorrect\":true").contains("\"needsReview\":true");
    }

    @Test
    void anAdministratorAlsoGetsTheAnswer() {
        givenAFullStatement();

        String json = bodyAsJson(adminJwt());

        assertThat(json).contains("\"isCorrect\":true");
    }

    @Test
    void aStudentDoesNotReachADraft() {
        givenAFullStatement();

        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/statements/" + STATEMENT_ID + "/full")
                .exchange()
                .expectStatus().isOk();

        // The student branch must not have fallen through to the staff query,
        // which does not filter on visibility. That the missing statement becomes
        // a 404 is the service's contract, covered in StatementServiceTest.
        org.mockito.Mockito.verify(statementService).findByIdWithQuestionsVisible(STATEMENT_ID);
        org.mockito.Mockito.verify(statementService, org.mockito.Mockito.never())
                .findByIdWithQuestions(anyLong());
    }

    @Test
    void staffReachTheDraftThroughTheUnfilteredQuery() {
        statement.setVisible(false);
        givenAFullStatement();

        client.mutateWith(teacherJwt())
                .get()
                .uri("/api/v1/statements/" + STATEMENT_ID + "/full")
                .exchange()
                .expectStatus().isOk();

        org.mockito.Mockito.verify(statementService).findByIdWithQuestions(STATEMENT_ID);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private void givenAFullStatement() {
        Question question = new Question(STATEMENT_ID, 1, "Resolva x+1=2", "multiple_choice");
        question.setId(QUESTION_ID);
        question.setNeedsReview(true);

        QuestionOption right = new QuestionOption(QUESTION_ID, "B", "x = 1", true);
        right.setId(OPTION_ID);
        QuestionOption wrong = new QuestionOption(QUESTION_ID, "A", "x = 2", false);
        wrong.setId(OPTION_ID - 1);

        when(statementService.findByIdWithQuestions(STATEMENT_ID))
                .thenReturn(Mono.just(new StatementWithQuestions(
                        statement, List.of(question), List.of(wrong, right))));
        when(statementService.findByIdWithQuestionsVisible(STATEMENT_ID))
                .thenReturn(Mono.just(new StatementWithQuestions(
                        statement, List.of(question), List.of(wrong, right))));
    }

    private String bodyAsJson(WebTestClientConfigurer authentication) {
        byte[] body = client.mutateWith(authentication)
                .get()
                .uri("/api/v1/statements/" + STATEMENT_ID + "/full")
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static WebTestClientConfigurer teacherJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(TEACHER_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }

    private static WebTestClientConfigurer adminJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "1",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private static WebTestClientConfigurer studentJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(STUDENT_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }
}