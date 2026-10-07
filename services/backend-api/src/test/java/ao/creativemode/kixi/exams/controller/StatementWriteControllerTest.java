package ao.creativemode.kixi.exams.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.exams.dto.statement.StatementRequest;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.service.ManualStatementService;
import ao.creativemode.kixi.exams.service.StatementService;
import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
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
import reactor.core.publisher.Mono;

/**
 * The two write routes of #103, over HTTP.
 *
 * <p>The services are mocked here on purpose: this is the controller's contract —
 * which route, which body, which status, and which account reaches the service.
 * What the service then does to the data is covered by {@code StatementServiceTest}
 * and {@code ManualStatementServiceTest}; the authorization those services enforce
 * is covered with the real chain in {@code StatementApprovalAuthorizationTest}.
 */
@WebFluxTest(controllers = {StatementController.class, ManualStatementController.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class StatementWriteControllerTest {

    private static final Long TEACHER_ID = 7L;
    private static final Long ADMIN_ID = 1L;

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
        statement = new Statement("P1", "Prova de Matemática");
        statement.setId(10L);
        statement.setSource("manual");
        statement.setVisible(false);
        statement.setNeedsReview(false);
        statement.setInstitutionId(1L);
        statement.setSubjectId(2L);
        statement.setClassId(8L);
    }

    @Test
    void createsAStatementAndPointsAtIt() {
        when(manualStatementService.create(any(), anyLong(), anyBoolean())).thenReturn(Mono.just(statement));

        client.mutateWith(teacherJwt())
                .post()
                .uri("/api/v1/statements")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(manualPayload())
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueMatches("Location", ".*/api/v1/statements/10")
                .expectBody()
                .jsonPath("$.id").isEqualTo(10)
                .jsonPath("$.source").isEqualTo("manual");

        verify(manualStatementService).create(any(), eq(TEACHER_ID), eq(false));
    }

    @Test
    void theOlderManualRouteCreatesTheSameStatement() {
        when(manualStatementService.create(any(), anyLong(), anyBoolean())).thenReturn(Mono.just(statement));

        client.mutateWith(teacherJwt())
                .post()
                .uri("/api/v1/statements/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(manualPayload())
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.source").isEqualTo("manual");

        verify(manualStatementService).create(any(), eq(TEACHER_ID), eq(false));
    }

    @Test
    void anAdministratorIsFlaggedWhenCreating() {
        when(manualStatementService.create(any(), anyLong(), anyBoolean())).thenReturn(Mono.just(statement));

        client.mutateWith(adminJwt())
                .post()
                .uri("/api/v1/statements")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(manualPayload())
                .exchange()
                .expectStatus().isCreated();

        verify(manualStatementService).create(any(), eq(ADMIN_ID), eq(true));
    }

    @Test
    void rejectsAStatementWithoutATitle() {
        client.mutateWith(teacherJwt())
                .post()
                .uri("/api/v1/statements")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "institutionId", 1L,
                        "subjectId", 2L,
                        "examType", "P1",
                        "questions", List.of(Map.of("text", "Resolva x+1=2"))))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void rejectsAStatementWithoutQuestions() {
        client.mutateWith(teacherJwt())
                .post()
                .uri("/api/v1/statements")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "institutionId", 1L,
                        "subjectId", 2L,
                        "examType", "P1",
                        "title", "Prova de Matemática"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void updatesTheMetadataAndPassesTheAccount() {
        when(statementService.update(anyLong(), any(), anyLong(), anyBoolean()))
                .thenReturn(Mono.just(statement));

        client.mutateWith(teacherJwt())
                .put()
                .uri("/api/v1/statements/10")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(metadataPayload())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.examType").isEqualTo("P1");

        verify(statementService).update(eq(10L), any(StatementRequest.class), eq(TEACHER_ID), eq(false));
    }

    @Test
    void rejectsMetadataWithoutAnInstitution() {
        client.mutateWith(teacherJwt())
                .put()
                .uri("/api/v1/statements/10")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "subjectId", 2L,
                        "examType", "P1",
                        "title", "Prova de Matemática"))
                .exchange()
                .expectStatus().isBadRequest();

        org.mockito.Mockito.verifyNoInteractions(statementService);
    }

    // ── Payloads ────────────────────────────────────────────────────────────

    private Map<String, Object> manualPayload() {
        return Map.of(
                "institutionId", 1L,
                "subjectId", 2L,
                "classId", 8L,
                "examType", "P1",
                "title", "Prova de Matemática",
                "questions", List.of(Map.of("text", "Resolva x+1=2", "maxScore", 5.0)));
    }

    private Map<String, Object> metadataPayload() {
        return Map.of(
                "institutionId", 1L,
                "subjectId", 2L,
                "classId", 8L,
                "examType", "P1",
                "title", "Prova de Matemática",
                "totalMaxScore", 20.0);
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
}
