package ao.creativemode.kixi.exams.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.service.ManualStatementService;
import ao.creativemode.kixi.exams.service.StatementReadAccessService;
import ao.creativemode.kixi.exams.service.StatementReadScope;
import ao.creativemode.kixi.exams.service.StatementService;
import ao.creativemode.kixi.exams.service.StatementWithQuestions;
import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.exception.ApiException;
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
 * O enunciado virtual de um treino por temas (kixi#729) lê-se só pelo aluno dono, sem gabarito; para qualquer outra
 * conta, seja aluno, professor ou administrador, é «não encontrado» como se não existisse.
 */
@WebFluxTest(controllers = StatementController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class StatementPracticeAccessTest {

    private static final long OWNER = 12L;
    private static final long OTHER_STUDENT = 13L;
    private static final long TEACHER = 7L;
    private static final long PRACTICE = 7000L;

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private StatementService statementService;

    @MockBean
    private ManualStatementService manualStatementService;

    @MockBean
    private StatementReadAccessService statementReadAccess;

    @BeforeEach
    void setUp() {
        when(statementReadAccess.teacherScope(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(StatementReadScope.NO_ASSIGNMENTS));
        Statement practice = new Statement("Treino", "Treino · Matriz");
        practice.setId(PRACTICE);
        practice.setVisible(false);
        practice.setPracticeOwnerAccountId(OWNER);
        Question question = new Question(PRACTICE, 1, "Resolva x+1=2", "multiple_choice");
        question.setId(20L);
        QuestionOption option = new QuestionOption(20L, "B", "x = 1", true);
        option.setId(30L);
        when(statementService.findOwnPractice(PRACTICE, OWNER)).thenReturn(Mono.just(practice));
        when(statementService.findOwnPracticeWithQuestions(PRACTICE, OWNER))
                .thenReturn(Mono.just(new StatementWithQuestions(practice, List.of(question), List.of(option))));
        when(statementService.findOwnPractice(PRACTICE, OTHER_STUDENT)).thenReturn(Mono.empty());
        when(statementService.findOwnPracticeWithQuestions(PRACTICE, OTHER_STUDENT)).thenReturn(Mono.empty());
        when(statementService.findOwnPractice(PRACTICE, TEACHER)).thenReturn(Mono.empty());
        when(statementService.findOwnPracticeWithQuestions(PRACTICE, TEACHER)).thenReturn(Mono.empty());
        when(statementService.findByIdVisible(PRACTICE)).thenReturn(Mono.error(ApiException.notFound("Statement not found: " + PRACTICE)));
        when(statementService.findById(PRACTICE)).thenReturn(Mono.error(ApiException.notFound("Statement not found: " + PRACTICE)));
        when(statementService.findByIdWithQuestionsVisible(PRACTICE)).thenReturn(Mono.error(ApiException.notFound("Statement not found: " + PRACTICE)));
        when(statementService.findByIdWithQuestions(PRACTICE)).thenReturn(Mono.error(ApiException.notFound("Statement not found: " + PRACTICE)));
    }

    @Test
    void theOwnerReadsItsPracticeWithoutTheAnswerKey() {
        String json = client.mutateWith(as(OWNER, "STUDENT")).get().uri("/api/v1/statements/" + PRACTICE + "/full")
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        assertThat(json).contains("Resolva x+1=2").contains("x = 1").doesNotContain("isCorrect");
        verify(statementService, never()).findByIdWithQuestionsVisible(anyLong());
        client.mutateWith(as(OWNER, "STUDENT")).get().uri("/api/v1/statements/" + PRACTICE).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.title").isEqualTo("Treino · Matriz");
    }

    @Test
    void anotherStudentATeacherAndAnAdminGetNotFound() {
        client.mutateWith(as(OTHER_STUDENT, "STUDENT")).get().uri("/api/v1/statements/" + PRACTICE + "/full").exchange().expectStatus().isNotFound();
        client.mutateWith(as(OTHER_STUDENT, "STUDENT")).get().uri("/api/v1/statements/" + PRACTICE).exchange().expectStatus().isNotFound();
        client.mutateWith(as(TEACHER, "TEACHER")).get().uri("/api/v1/statements/" + PRACTICE + "/full").exchange().expectStatus().isNotFound();
        client.mutateWith(as(TEACHER, "TEACHER")).get().uri("/api/v1/statements/" + PRACTICE).exchange().expectStatus().isNotFound();
    }

    private static WebTestClientConfigurer as(long id, String role) {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(id), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
