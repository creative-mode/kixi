package ao.creativemode.kixi.exams.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

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
 * The nested option routes over HTTP.
 *
 * <p>Three ids deep, so what matters here is that every one of them reaches the
 * service. Dropping the statement id from the call would leave the write rule
 * weighing the wrong statement, and the check that the option belongs to the
 * question would have nothing to check against.
 */
@WebFluxTest(controllers = {QuestionOptionController.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class QuestionOptionControllerTest {

    private static final Long TEACHER_ID = 7L;
    private static final Long STUDENT_ID = 12L;
    private static final Long STATEMENT_ID = 10L;
    private static final Long QUESTION_ID = 20L;
    private static final Long OPTION_ID = 30L;

    private static final String OPTIONS =
            "/api/v1/statements/" + STATEMENT_ID + "/questions/" + QUESTION_ID + "/options";

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private QuestionOptionService questionOptionService;

    @MockBean
    private QuestionService questionService;

    @MockBean
    private CurrentAccountService currentAccountService;

    @BeforeEach
    void setUp() {
        when(currentAccountService.requiredAccountId()).thenReturn(Mono.just(TEACHER_ID));
        when(currentAccountService.hasAnyRole("ADMIN", "TEACHER")).thenReturn(Mono.just(true));
        when(currentAccountService.hasAnyRole("ADMIN")).thenReturn(Mono.just(false));
        when(questionOptionService.findAll(STATEMENT_ID, QUESTION_ID, true))
                .thenReturn(Flux.just(optionResponse()));
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    @Test
    void listsTheOptionsOfTheQuestionUnderBothParents() {
        client.mutateWith(teacherJwt())
                .get()
                .uri(OPTIONS)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo(OPTION_ID)
                .jsonPath("$[0].questionId").isEqualTo(QUESTION_ID);

        verify(questionOptionService).findAll(STATEMENT_ID, QUESTION_ID, true);
    }

    @Test
    void aStudentIsAskedForTheReaderViewOfTheOptions() {
        // The absence of isCorrect in the body is the service's doing — it is
        // covered in QuestionOptionServiceTest — so what this pins is that the
        // controller asks for the reader's view at all, rather than for the staff
        // one and dropping the field on the way out.
        when(currentAccountService.hasAnyRole("ADMIN", "TEACHER")).thenReturn(Mono.just(false));
        when(questionOptionService.findAll(STATEMENT_ID, QUESTION_ID, false))
                .thenReturn(Flux.just(optionResponse()));

        client.mutateWith(studentJwt())
                .get()
                .uri(OPTIONS)
                .exchange()
                .expectStatus().isOk();

        verify(questionOptionService).findAll(STATEMENT_ID, QUESTION_ID, false);
    }

    @Test
    void listsTheRemovedOptionsOnTheirOwnPath() {
        when(questionOptionService.findAllDeleted(STATEMENT_ID, QUESTION_ID, true))
                .thenReturn(Flux.just(optionResponse()));

        client.mutateWith(teacherJwt())
                .get()
                .uri(OPTIONS + "/trash")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void findsOneOption() {
        when(questionOptionService.findById(STATEMENT_ID, QUESTION_ID, OPTION_ID, true))
                .thenReturn(Mono.just(optionResponse()));

        client.mutateWith(teacherJwt())
                .get()
                .uri(OPTIONS + "/" + OPTION_ID)
                .exchange()
                .expectStatus().isOk();
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    @Test
    void createsAnOptionAndPointsAtIt() {
        when(questionOptionService.create(eq(STATEMENT_ID), eq(QUESTION_ID), any(), anyLong(), anyBoolean()))
                .thenReturn(Mono.just(optionResponse()));

        client.mutateWith(teacherJwt())
                .post()
                .uri(OPTIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("optionLabel", "B", "optionText", "x = 1"))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals(
                    "Location", "/api/v1/statements/10/questions/20/options/30")
                .expectBody()
                .jsonPath("$.optionLabel").isEqualTo("B");
    }

    @Test
    void anOptionWithoutALabelIsRefusedBeforeTheService() {
        // The label is the option's identity and is unique per question; the
        // table will not say which of two texts was meant.
        client.mutateWith(teacherJwt())
                .post()
                .uri(OPTIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("optionLabel", "  ", "optionText", "x = 1"))
                .exchange()
                .expectStatus().isBadRequest();

        verify(questionOptionService, never())
                .create(anyLong(), anyLong(), any(), anyLong(), anyBoolean());
    }

    @Test
    void anOptionCanBeMarkedCorrectAsItIsAdded() {
        // Creating with isCorrect is the other way in, next to the correct-option
        // route. It has to end with the same thing: one answer.
        when(questionOptionService.create(eq(STATEMENT_ID), eq(QUESTION_ID), any(), anyLong(), anyBoolean()))
                .thenReturn(Mono.just(optionResponse()));

        client.mutateWith(teacherJwt())
                .post()
                .uri(OPTIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("optionLabel", "B", "optionText", "x = 1", "isCorrect", true))
                .exchange()
                .expectStatus().isCreated();

        verify(questionOptionService).create(
                eq(STATEMENT_ID), eq(QUESTION_ID), any(), eq(TEACHER_ID), eq(false));
    }

    @Test
    void aStudentCannotAddAnOptionAlreadyMarkedCorrect() {
        client.mutateWith(studentJwt())
                .post()
                .uri(OPTIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("optionLabel", "B", "optionText", "x = 1", "isCorrect", true))
                .exchange()
                .expectStatus().isForbidden();

        verify(questionOptionService, never())
                .create(anyLong(), anyLong(), any(), anyLong(), anyBoolean());
    }

    @Test
    void updatesAnOptionText() {
        when(questionOptionService.update(
                eq(STATEMENT_ID), eq(QUESTION_ID), eq(OPTION_ID), any(), anyLong(), anyBoolean()))
                .thenReturn(Mono.just(optionResponse()));

        client.mutateWith(teacherJwt())
                .put()
                .uri(OPTIONS + "/" + OPTION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("optionLabel", "B", "optionText", "x = 1, unique"))
                .exchange()
                .expectStatus().isOk();

        verify(questionOptionService).update(
                eq(STATEMENT_ID), eq(QUESTION_ID), eq(OPTION_ID), any(), eq(TEACHER_ID), eq(false));
    }

    @Test
    void reordersOnItsOwnPathAndNotAsAnOptionId() {
        when(questionOptionService.reorder(eq(STATEMENT_ID), eq(QUESTION_ID), any(), anyLong(), anyBoolean()))
                .thenReturn(Flux.just(optionResponse()));

        client.mutateWith(teacherJwt())
                .put()
                .uri(OPTIONS + "/reorder")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("optionIds", List.of(OPTION_ID)))
                .exchange()
                .expectStatus().isOk();

        verify(questionOptionService)
                .reorder(eq(STATEMENT_ID), eq(QUESTION_ID), eq(List.of(OPTION_ID)), eq(TEACHER_ID), eq(false));
    }

    @Test
    void removesRestoresAndPurgesAnOption() {
        when(questionOptionService.softDelete(STATEMENT_ID, QUESTION_ID, OPTION_ID, TEACHER_ID, false))
                .thenReturn(Mono.empty());
        when(questionOptionService.restore(STATEMENT_ID, QUESTION_ID, OPTION_ID, TEACHER_ID, false))
                .thenReturn(Mono.empty());
        when(questionOptionService.hardDelete(STATEMENT_ID, QUESTION_ID, OPTION_ID, TEACHER_ID, false))
                .thenReturn(Mono.empty());

        client.mutateWith(teacherJwt())
                .delete()
                .uri(OPTIONS + "/" + OPTION_ID)
                .exchange()
                .expectStatus().isNoContent();

        client.mutateWith(teacherJwt())
                .post()
                .uri(OPTIONS + "/" + OPTION_ID + "/restore")
                .exchange()
                .expectStatus().isNoContent();

        client.mutateWith(teacherJwt())
                .delete()
                .uri(OPTIONS + "/" + OPTION_ID + "/purge")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void aStudentCannotWriteAnOption() {
        client.mutateWith(studentJwt())
                .delete()
                .uri(OPTIONS + "/" + OPTION_ID)
                .exchange()
                .expectStatus().isForbidden();

        verify(questionOptionService, never())
                .softDelete(anyLong(), anyLong(), anyLong(), anyLong(), anyBoolean());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private QuestionOptionResponse optionResponse() {
        return new QuestionOptionResponse(
                OPTION_ID, QUESTION_ID, "B", "x = 1", false, 1,
                LocalDateTime.now(), LocalDateTime.now(), null);
    }

    private static WebTestClientConfigurer teacherJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(TEACHER_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }

    private static WebTestClientConfigurer studentJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(STUDENT_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }
}
