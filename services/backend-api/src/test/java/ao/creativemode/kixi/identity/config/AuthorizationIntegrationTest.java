package ao.creativemode.kixi.identity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.ocr.client.OcrServiceClient;
import ao.creativemode.kixi.ocr.controller.OcrController;
import ao.creativemode.kixi.exams.controller.StatementController;
import ao.creativemode.kixi.ocr.dto.OcrResponse;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.ocr.service.OcrPersistenceService;
import ao.creativemode.kixi.ocr.service.OcrPersistenceService.StatementWithRelations;
import ao.creativemode.kixi.simulations.service.SimulationAnswerService;
import ao.creativemode.kixi.simulations.service.SimulationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClientConfigurer;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = {
        ao.creativemode.kixi.simulations.controller.SimulationController.class,
        ao.creativemode.kixi.simulations.controller.SimulationAnswerController.class,
        OcrController.class,
        StatementController.class,
        ao.creativemode.kixi.institutions.controller.TeachingAssignmentController.class
})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class, CurrentAccountService.class, JwtAuthenticationFilter.class,
        RequestIdWebFilter.class})
class AuthorizationIntegrationTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private SimulationService simulationService;

    @MockBean
    private ao.creativemode.kixi.simulations.service.SimulationResultService simulationResultService;

    @MockBean
    private SimulationAnswerService simulationAnswerService;

    @MockBean
    private OcrServiceClient ocrServiceClient;

    @MockBean
    private OcrPersistenceService ocrPersistenceService;

    @MockBean
    private ao.creativemode.kixi.exams.service.StatementService statementService;

    @MockBean
    private ao.creativemode.kixi.exams.service.ManualStatementService manualStatementService;

    @MockBean
    private ao.creativemode.kixi.institutions.service.TeachingAssignmentService teachingAssignmentService;

    /**
     * O springdoc não é carregado no slice {@code @WebFluxTest} (por isso 404);
     * o que se valida é a regra de segurança deixar o caminho passar — se não
     * deixasse, a resposta seria 401/403 antes de chegar ao handler.
     * O endpoint documentado a sério está verificado em ambiente real:
     * {@code GET /v3/api-docs} devolve 200 sem autenticação.
     */
    @Test
    void permitsAnonymousApiDocs() {
        client.get()
                .uri("/v3/api-docs")
                .exchange()
                .expectStatus()
                .value(status -> assertThat(status).isNotIn(401, 403));
    }

    @Test
    void rejectsAnonymousSimulationReads() {
        client.get()
                .uri("/api/simulations")
                .header("X-Request-ID", "spoofed")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueMatches("X-Request-ID", "req-[0-9a-f]+");
    }

    @Test
    void rejectsAnonymousSimulationReadsOnVersionedRoute() {
        client.get()
                .uri("/api/v1/simulations")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void scopesStudentSimulationReadsOnVersionedRoute() {
        when(simulationService.findAllActiveForAccount(42L)).thenReturn(Flux.empty());

        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/simulations")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");

        verify(simulationService).findAllActiveForAccount(42L);
    }

    @Test
    void rejectsStudentSimulationTrashOnVersionedRoute() {
        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/simulations/trash")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void rejectsStudentAccessToSimulationTrash() {
        client.mutateWith(studentJwt())
                .get()
                .uri("/api/simulations/trash")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void scopesStudentSimulationReadsToAuthenticatedAccount() {
        when(simulationService.findAllActiveForAccount(42L)).thenReturn(Flux.empty());

        client.mutateWith(studentJwt())
                .get()
                .uri("/api/simulations")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");

        verify(simulationService).findAllActiveForAccount(42L);
    }

    @Test
    void scopesStudentAnswerReadsToAuthenticatedAccount() {
        when(simulationAnswerService.findAllActiveForAccount(42L)).thenReturn(Flux.empty());

        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/simulation-answers")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");

        verify(simulationAnswerService).findAllActiveForAccount(42L);
    }

    @Test
    void rejectsStudentDeleteOfSimulation() {
        client.mutateWith(studentJwt())
                .delete()
                .uri("/api/simulations/1")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void rejectsAnonymousOcrExtraction() {
        client.post()
                .uri("/api/v1/ocr/extract/single")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsStudentOcrExtraction() {
        client.mutateWith(studentJwt())
                .post()
                .uri("/api/v1/ocr/extract/single")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void allowsTeacherOcrExtractionAndCallsClient() {
        when(ocrServiceClient.extractText(anyList())).thenReturn(Mono.just(successfulOcrResponse()));

        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", "exam-content".getBytes())
                .filename("exam.png")
                .contentType(MediaType.IMAGE_PNG);

        client.mutateWith(teacherJwt())
                .post()
                .uri("/api/v1/ocr/extract/single")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .exchange()
                .expectStatus().isOk();

        verify(ocrServiceClient).extractText(anyList());
    }

    @Test
    void passesAuthenticatedAccountToOcrPersistence() {
        Statement statement = new Statement("EXAM", "Mathematics exam");
        statement.setId(9L);
        when(ocrPersistenceService.processAndPersist(anyList(), eq(7L)))
                .thenReturn(Mono.just(new StatementWithRelations(
                        statement,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of()
                )));

        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("files", "exam-content".getBytes())
                .filename("exam.png")
                .contentType(MediaType.IMAGE_PNG);

        client.mutateWith(teacherJwt())
                .post()
                .uri("/api/v1/ocr/extract-and-persist")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .exchange()
                .expectStatus().isCreated();

        verify(ocrPersistenceService).processAndPersist(anyList(), eq(7L));
    }

    @Test
    void scopesStudentStatementReadsToVisibleStatements() {
        when(statementService.findAllVisible()).thenReturn(Flux.empty());

        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/statements")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");

        verify(statementService).findAllVisible();
        org.mockito.Mockito.verify(statementService, org.mockito.Mockito.never()).findAllActive();
    }

    @Test
    void allowsStaffStatementReadsToIncludePendingStatements() {
        when(statementService.findAllActive()).thenReturn(Flux.empty());

        client.mutateWith(teacherJwt())
                .get()
                .uri("/api/v1/statements")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");

        verify(statementService).findAllActive();
        org.mockito.Mockito.verify(statementService, org.mockito.Mockito.never()).findAllVisible();
    }

    private static OcrResponse successfulOcrResponse() {
        return new OcrResponse(
                "success",
                "request-1",
                10,
                0.95,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null
        );
    }

    // ── Statement writes carry the signed-in account to the service ─────────

    @Test
    void passesTheSignedInTeacherWhenApprovingAStatement() {
        givenApproveReviewReturnsAStatement();

        client.mutateWith(teacherJwt())
                .post()
                .uri("/api/v1/statements/1/approve")
                .exchange()
                .expectStatus().isOk();

        verify(statementService).approveReview(1L, 7L, false);
    }

    @Test
    void passesTheAdministratorFlagWhenApprovingAStatement() {
        givenApproveReviewReturnsAStatement();

        client.mutateWith(adminJwt())
                .post()
                .uri("/api/v1/statements/1/approve")
                .exchange()
                .expectStatus().isOk();

        verify(statementService).approveReview(1L, 1L, true);
    }

    @Test
    void passesTheSignedInTeacherWhenChangingVisibility() {
        Statement statement = new Statement("EXAM", "Mathematics exam");
        statement.setId(1L);
        when(statementService.setVisible(anyLong(), anyBoolean(), anyLong(), anyBoolean()))
                .thenReturn(Mono.just(statement));

        client.mutateWith(teacherJwt())
                .patch()
                .uri("/api/v1/statements/1/visibility?visible=true")
                .exchange()
                .expectStatus().isOk();

        verify(statementService).setVisible(1L, true, 7L, false);
    }

    @Test
    void passesTheSignedInTeacherWhenDeletingAStatement() {
        when(statementService.softDelete(anyLong(), anyLong(), anyBoolean())).thenReturn(Mono.empty());

        client.mutateWith(teacherJwt())
                .delete()
                .uri("/api/v1/statements/1")
                .exchange()
                .expectStatus().isNoContent();

        verify(statementService).softDelete(1L, 7L, false);
    }

    @Test
    void passesTheSignedInTeacherWhenPurgingAStatement() {
        when(statementService.hardDelete(anyLong(), anyLong(), anyBoolean())).thenReturn(Mono.empty());

        client.mutateWith(teacherJwt())
                .delete()
                .uri("/api/v1/statements/1/purge")
                .exchange()
                .expectStatus().isNoContent();

        verify(statementService).hardDelete(1L, 7L, false);
    }

    @Test
    void passesTheSignedInTeacherWhenRestoringAStatement() {
        when(statementService.restore(anyLong(), anyLong(), anyBoolean())).thenReturn(Mono.empty());

        client.mutateWith(teacherJwt())
                .post()
                .uri("/api/v1/statements/1/restore")
                .exchange()
                .expectStatus().isNoContent();

        verify(statementService).restore(1L, 7L, false);
    }

    private void givenApproveReviewReturnsAStatement() {
        Statement statement = new Statement("EXAM", "Mathematics exam");
        statement.setId(1L);
        when(statementService.approveReview(anyLong(), anyLong(), anyBoolean()))
                .thenReturn(Mono.just(statement));
    }

    // ── Teaching assignments: administered by ADMIN, read by the teacher ────

    @Test
    void rejectsAnonymousTeachingAssignmentReads() {
        client.get()
                .uri("/api/v1/teaching-assignments")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void allowsAdminToListTeachingAssignments() {
        when(teachingAssignmentService.findAllActive()).thenReturn(Flux.empty());

        client.mutateWith(adminJwt())
                .get()
                .uri("/api/v1/teaching-assignments")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");

        verify(teachingAssignmentService).findAllActive();
    }

    @Test
    void allowsTeacherToReadItsOwnTeachingAssignments() {
        when(teachingAssignmentService.findMineForAccount(7L)).thenReturn(Flux.empty());

        client.mutateWith(teacherJwt())
                .get()
                .uri("/api/v1/teaching-assignments/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");

        verify(teachingAssignmentService).findMineForAccount(7L);
    }

    @Test
    void rejectsTeacherReadingOtherTeachingAssignments() {
        client.mutateWith(teacherJwt())
                .get()
                .uri("/api/v1/teaching-assignments")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void rejectsTeacherReadingTheTeachingAssignmentTrash() {
        client.mutateWith(teacherJwt())
                .get()
                .uri("/api/v1/teaching-assignments/trash")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void rejectsTeacherCreatingTeachingAssignments() {
        client.mutateWith(teacherJwt())
                .post()
                .uri("/api/v1/teaching-assignments")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(assignmentPayload())
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(teachingAssignmentService);
    }

    @Test
    void rejectsStudentCreatingTeachingAssignments() {
        client.mutateWith(studentJwt())
                .post()
                .uri("/api/v1/teaching-assignments")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(assignmentPayload())
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(teachingAssignmentService);
    }

    @Test
    void rejectsStudentReadingItsOwnTeachingAssignments() {
        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/teaching-assignments/me")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void rejectsTeacherDeletingTeachingAssignments() {
        client.mutateWith(teacherJwt())
                .delete()
                .uri("/api/v1/teaching-assignments/1")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(teachingAssignmentService);
    }

    private static Map<String, Object> assignmentPayload() {
        return Map.of("teacherId", 1L, "classId", 2L, "subjectId", 3L, "schoolYearId", 4L);
    }

    private static WebTestClientConfigurer studentJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "42",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))
        ));
    }

    private static WebTestClientConfigurer teacherJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "7",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))
        ));
    }

    private static WebTestClientConfigurer adminJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "1",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        ));
    }
}
