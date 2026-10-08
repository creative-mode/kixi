package ao.creativemode.kixi.exams.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.exams.dto.question.QuestionResponse;
import ao.creativemode.kixi.exams.dto.questionoption.QuestionOptionResponse;
import ao.creativemode.kixi.exams.service.QuestionOptionService;
import ao.creativemode.kixi.exams.service.QuestionService;
import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClientConfigurer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The nested question routes over HTTP, and the answer key on them.
 *
 * <p>The services are mocked on purpose: this is the controller's contract —
 * which route, which body, which status, and which ids reach the service. What the
 * service then does to the data is in {@code QuestionServiceTest} and
 * {@code QuestionOptionServiceTest}; the authorization it enforces is with the real
 * chain in {@code StatementWriteAuthorizationTest}.
 *
 * <p>The status a student gets on the answer key is pinned here on purpose. Reads
 * under /statements are open to any authenticated caller, and it would be easy to
 * assume that carries over to marking an answer.
 */
@WebFluxTest(controllers = {QuestionController.class, QuestionOptionController.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class QuestionControllerTest {

    private static final Long TEACHER_ID = 7L;
    private static final Long ADMIN_ID = 1L;
    private static final Long STUDENT_ID = 12L;
    private static final Long STATEMENT_ID = 10L;
    private static final Long QUESTION_ID = 20L;

    private static final String QUESTIONS = "/api/v1/statements/" + STATEMENT_ID + "/questions";

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private QuestionService questionService;

    @MockBean
    private QuestionOptionService questionOptionService;

    @BeforeEach
    void setUp() {
        when(questionService.findAllActive(STATEMENT_ID)).thenReturn(Flux.just(questionResponse()));
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    @Test
    void listsTheQuestionsOfTheStatementInThePath() {
        client.mutateWith(teacherJwt())
                .get()
                .uri(QUESTIONS)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo(QUESTION_ID)
                .jsonPath("$[0].number").isEqualTo(1)
                .jsonPath("$[0].modelAnswer").isEqualTo("2");

        verify(questionService).findAllActive(STATEMENT_ID);
    }

    @Test
    void aStudentMayReadTheQuestions() {
        client.mutateWith(studentJwt())
                .get()
                .uri(QUESTIONS)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void listsTheRemovedQuestionsOnTheirOwnPath() {
        when(questionService.findAllDeleted(STATEMENT_ID)).thenReturn(Flux.just(questionResponse()));

        client.mutateWith(teacherJwt())
                .get()
                .uri(QUESTIONS + "/trash")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo(QUESTION_ID);
    }

    @Test
    void readingWithoutASessionIsRefused() {
        client.get()
                .uri(QUESTIONS)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    @Test
    void createsAQuestionAndPointsAtIt() {
        when(questionService.create(eq(STATEMENT_ID), any(), anyLong(), anyBoolean()))
                .thenReturn(Mono.just(questionResponse()));

        client.mutateWith(teacherJwt())
                .post()
                .uri(QUESTIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "Resolva x+1=2", "maxScore", 5.0, "modelAnswer", "2"))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals(
                    "Location", "/api/v1/statements/10/questions/20")
                .expectBody()
                .jsonPath("$.modelAnswer").isEqualTo("2");
    }

    @Test
    void theNumberIsNotAcceptedFromTheCaller() {
        // number is the stable ordinal, unique per statement, so it is assigned
        // from what the statement already holds rather than taken from a body.
        when(questionService.create(eq(STATEMENT_ID), any(), anyLong(), anyBoolean()))
                .thenReturn(Mono.just(questionResponse()));

        client.mutateWith(teacherJwt())
                .post()
                .uri(QUESTIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "Resolva x+1=2", "number", 99))
                .exchange()
                .expectStatus().isCreated();

        verify(questionService).create(eq(STATEMENT_ID), any(), eq(TEACHER_ID), eq(false));
    }

    @Test
    void aQuestionWithoutTextIsRefusedBeforeTheService() {
        client.mutateWith(teacherJwt())
                .post()
                .uri(QUESTIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "   ", "maxScore", 5.0))
                .exchange()
                .expectStatus().isBadRequest();

        verify(questionService, never()).create(anyLong(), any(), anyLong(), anyBoolean());
    }

    @Test
    void aNegativeScoreIsRefused() {
        client.mutateWith(teacherJwt())
                .post()
                .uri(QUESTIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "Resolva", "maxScore", -1.0))
                .exchange()
                .expectStatus().isBadRequest();

        verify(questionService, never()).create(anyLong(), any(), anyLong(), anyBoolean());
    }

    @Test
    void updatesAQuestionUnderItsStatement() {
        when(questionService.update(eq(STATEMENT_ID), eq(QUESTION_ID), any(), anyLong(), anyBoolean()))
                .thenReturn(Mono.just(questionResponse()));

        client.mutateWith(teacherJwt())
                .put()
                .uri(QUESTIONS + "/" + QUESTION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "revisado", "maxScore", 5.0))
                .exchange()
                .expectStatus().isOk();

        verify(questionService).update(eq(STATEMENT_ID), eq(QUESTION_ID), any(), eq(TEACHER_ID), eq(false));
    }

    @Test
    void reordersOnItsOwnPathAndNotAsAQuestionId() {
        when(questionService.reorder(eq(STATEMENT_ID), any(), anyLong(), anyBoolean()))
                .thenReturn(Flux.just(questionResponse()));

        client.mutateWith(teacherJwt())
                .put()
                .uri(QUESTIONS + "/reorder")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("questionIds", List.of(QUESTION_ID)))
                .exchange()
                .expectStatus().isOk();

        verify(questionService).reorder(eq(STATEMENT_ID), eq(List.of(QUESTION_ID)), eq(TEACHER_ID), eq(false));
    }

    @Test
    void anEmptyReorderListIsRefused() {
        client.mutateWith(teacherJwt())
                .put()
                .uri(QUESTIONS + "/reorder")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("questionIds", List.of()))
                .exchange()
                .expectStatus().isBadRequest();

        verify(questionService, never()).reorder(anyLong(), any(), anyLong(), anyBoolean());
    }

    @Test
    void removesAndRestoresAQuestion() {
        when(questionService.softDelete(STATEMENT_ID, QUESTION_ID, TEACHER_ID, false))
                .thenReturn(Mono.empty());
        when(questionService.restore(STATEMENT_ID, QUESTION_ID, TEACHER_ID, false))
                .thenReturn(Mono.empty());

        client.mutateWith(teacherJwt())
                .delete()
                .uri(QUESTIONS + "/" + QUESTION_ID)
                .exchange()
                .expectStatus().isNoContent();

        client.mutateWith(teacherJwt())
                .post()
                .uri(QUESTIONS + "/" + QUESTION_ID + "/restore")
                .exchange()
                .expectStatus().isNoContent();

        verify(questionService).softDelete(STATEMENT_ID, QUESTION_ID, TEACHER_ID, false);
        verify(questionService).restore(STATEMENT_ID, QUESTION_ID, TEACHER_ID, false);
    }

    @Test
    void purgesAQuestion() {
        when(questionService.hardDelete(STATEMENT_ID, QUESTION_ID, TEACHER_ID, false))
                .thenReturn(Mono.empty());

        client.mutateWith(teacherJwt())
                .delete()
                .uri(QUESTIONS + "/" + QUESTION_ID + "/purge")
                .exchange()
                .expectStatus().isNoContent();

        verify(questionService).hardDelete(STATEMENT_ID, QUESTION_ID, TEACHER_ID, false);
    }

    @Test
    void aStudentCannotWriteAQuestion() {
        client.mutateWith(studentJwt())
                .post()
                .uri(QUESTIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "Resolva", "maxScore", 5.0))
                .exchange()
                .expectStatus().isForbidden();

        verify(questionService, never()).create(anyLong(), any(), anyLong(), anyBoolean());
    }

    // ── The answer key ──────────────────────────────────────────────────────

    @Test
    void marksTheCorrectOptionUnderTheQuestion() {
        when(questionOptionService
                .setCorrectOption(STATEMENT_ID, QUESTION_ID, 30L, TEACHER_ID, false))
                .thenReturn(Mono.just(optionResponse(30L, true)));

        client.mutateWith(teacherJwt())
                .put()
                .uri(QUESTIONS + "/" + QUESTION_ID + "/correct-option")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("optionId", 30L))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(30L)
                .jsonPath("$.isCorrect").isEqualTo(true);

        verify(questionOptionService)
                .setCorrectOption(STATEMENT_ID, QUESTION_ID, 30L, TEACHER_ID, false);
    }

    @Test
    void aStudentCannotMarkAnAnswer() {
        // Reading the questions is open to any authenticated caller, so the
        // answer key needed pinning separately: it is not another read.
        client.mutateWith(studentJwt())
                .put()
                .uri(QUESTIONS + "/" + QUESTION_ID + "/correct-option")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("optionId", 30L))
                .exchange()
                .expectStatus().isForbidden();

        verify(questionOptionService, never())
                .setCorrectOption(anyLong(), anyLong(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void markingAnAnswerWithoutAnOptionIdIsRefused() {
        client.mutateWith(teacherJwt())
                .put()
                .uri(QUESTIONS + "/" + QUESTION_ID + "/correct-option")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isBadRequest();

        verify(questionOptionService, never())
                .setCorrectOption(anyLong(), anyLong(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void markingAnAnswerWithoutASessionIsRefused() {
        client.put()
                .uri(QUESTIONS + "/" + QUESTION_ID + "/correct-option")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("optionId", 30L))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Payloads and fixtures ───────────────────────────────────────────────

    private QuestionResponse questionResponse() {
        return new QuestionResponse(
                QUESTION_ID, STATEMENT_ID, 1, "Resolva x+1=2", "open",
                5.0, 0, 0, "2", false,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private QuestionOptionResponse optionResponse(Long id, boolean correct) {
        return new QuestionOptionResponse(
                id, QUESTION_ID, "B", "x = 1", correct, 1,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private static WebTestClientConfigurer teacherJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(TEACHER_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }

    private static WebTestClientConfigurer adminJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(ADMIN_ID),
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
