package ao.creativemode.kixi.identity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;
import static ao.creativemode.kixi.donations.service.DonationFixtures.png;

import ao.creativemode.kixi.ocr.client.OcrServiceClient;
import ao.creativemode.kixi.ocr.controller.OcrController;
import ao.creativemode.kixi.exams.controller.StatementController;
import ao.creativemode.kixi.ocr.dto.OcrResponse;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.controller.AccountController;
import ao.creativemode.kixi.identity.dto.accounts.AccountAccessibilityRequest;
import ao.creativemode.kixi.identity.dto.accounts.AccountResponse;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.service.AccountService;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.shared.service.ApiAbuseRateLimiter;
import ao.creativemode.kixi.moderation.controller.ModerationController;
import ao.creativemode.kixi.moderation.model.ModerationReport;
import ao.creativemode.kixi.moderation.repository.ModerationBlockRepository;
import ao.creativemode.kixi.moderation.repository.ModerationReportRepository;
import ao.creativemode.kixi.moderation.service.ModerationService;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.ocr.service.OcrImageAssociationService;
import ao.creativemode.kixi.ocr.service.OcrPersistenceService;
import ao.creativemode.kixi.ocr.service.OcrPersistenceService.StatementWithRelations;
import ao.creativemode.kixi.simulations.service.SimulationAnswerService;
import ao.creativemode.kixi.simulations.service.SimulationService;
import ao.creativemode.kixi.simulations.service.SimulationSubmissionService;
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
        AccountController.class,
        StatementController.class,
        ao.creativemode.kixi.institutions.controller.TeachingAssignmentController.class,
        ModerationController.class
})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class, CurrentAccountService.class, JwtAuthenticationFilter.class,
        RequestIdWebFilter.class, ModerationService.class})
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
    private SimulationSubmissionService simulationSubmissionService;

    @MockBean
    private SimulationAnswerService simulationAnswerService;

    @MockBean
    private AccountService accountService;

    @MockBean
    private OcrServiceClient ocrServiceClient;

    @MockBean
    private ApiAbuseRateLimiter abuseRateLimiter;

    @MockBean
    private AccountRepository accountRepository;

    @MockBean
    private InstitutionRepository institutionRepository;

    @MockBean
    private ModerationBlockRepository moderationBlockRepository;

    @MockBean
    private ModerationReportRepository moderationReportRepository;

    @MockBean
    private OcrPersistenceService ocrPersistenceService;

    @MockBean
    private OcrImageAssociationService ocrImageAssociationService;

    @MockBean
    private ao.creativemode.kixi.exams.service.StatementService statementService;

    @MockBean
    private ao.creativemode.kixi.exams.service.ManualStatementService manualStatementService;

    /** #174: a teacher's reads go through their scope. None of these tests is about it. */
    @MockBean
    private ao.creativemode.kixi.exams.service.StatementReadAccessService statementReadAccess;

    @org.junit.jupiter.api.BeforeEach
    void teachersReadTheStatementsNoClassOwns() {
        when(statementReadAccess.teacherScope(org.mockito.ArgumentMatchers.any()))
                .thenReturn(reactor.core.publisher.Mono.just(
                        ao.creativemode.kixi.exams.service.StatementReadScope.NO_ASSIGNMENTS));
        when(abuseRateLimiter.checkUpload(anyLong())).thenReturn(Mono.empty());
        when(abuseRateLimiter.checkReport(anyLong())).thenReturn(Mono.empty());
        when(accountRepository.findByIdAndDeletedAtIsNull(anyLong())).thenReturn(Mono.just(new Account()));
        when(moderationBlockRepository.existsPair(anyLong(), anyLong())).thenReturn(Mono.just(false));
        when(moderationBlockRepository.togglePair(anyLong(), anyLong())).thenReturn(Mono.just(true));
        when(moderationReportRepository.save(any(ModerationReport.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

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
    void moderationRoutesAreStudentOnly() {
        client.get()
                .uri("/api/v1/profiles/accounts/2/block")
                .exchange()
                .expectStatus().isUnauthorized();

        client.mutateWith(teacherJwt())
                .get()
                .uri("/api/v1/profiles/accounts/2/block")
                .exchange()
                .expectStatus().isForbidden();

        client.mutateWith(adminJwt())
                .post()
                .uri("/api/v1/institutions/2/reports")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"OTHER\",\"note\":\"spam\"}")
                .exchange()
                .expectStatus().isForbidden();

        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/profiles/accounts/2/block")
                .exchange()
                .expectStatus().isOk();

        client.mutateWith(studentJwt())
                .put()
                .uri("/api/v1/profiles/accounts/2/block")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("{\"blocked\":true}");

        client.mutateWith(studentJwt())
                .post()
                .uri("/api/v1/profiles/accounts/2/reports")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"OTHER\",\"note\":\"spam\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.target").isEqualTo("ACCOUNT")
                .jsonPath("$.targetId").isEqualTo(2)
                .jsonPath("$.reason").isEqualTo("OTHER");
    }

    @Test
    void deniesUnmappedRoutesToEveryAuthenticatedRole() {
        client.mutateWith(studentJwt())
                .post()
                .uri("/api/v1/unreviewed-write")
                .exchange()
                .expectStatus().isForbidden();

        client.mutateWith(teacherJwt())
                .post()
                .uri("/api/v1/unreviewed-write")
                .exchange()
                .expectStatus().isForbidden();

        client.mutateWith(adminJwt())
                .post()
                .uri("/api/v1/unreviewed-write")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void rejectsStudentWritesAndTrashReadsAcrossAcademicCatalogs() {
        client.mutateWith(studentJwt())
                .post()
                .uri("/api/v1/subjects")
                .exchange()
                .expectStatus().isForbidden();

        for (String path : List.of(
                "/api/v1/school-years/trash",
                "/api/v1/terms/trash",
                "/api/v1/subjects/trash",
                "/api/v1/courses/trash",
                "/api/v1/classes/trash",
                "/api/v1/question-images/trash")) {
            client.mutateWith(studentJwt())
                    .get()
                    .uri(path)
                    .exchange()
                    .expectStatus().isForbidden();
        }
    }

    @Test
    void keepsTheUnscopedQuestionImageCatalogAdminOnly() {
        client.get()
                .uri("/api/v1/question-images")
                .exchange()
                .expectStatus().isUnauthorized();

        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/question-images")
                .exchange()
                .expectStatus().isForbidden();

        client.mutateWith(teacherJwt())
                .get()
                .uri("/api/v1/question-images")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void rejectsAnonymousSimulationReadsOnVersionedRoute() {
        client.get()
                .uri("/api/v1/simulations")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void protectsExamRoomLiveStreamFromAnonymousAndStudents() {
        client.get()
                .uri("/api/v1/exam-rooms/1/live")
                .exchange()
                .expectStatus().isUnauthorized();

        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/exam-rooms/1/live")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void rejectsStudentPatchingAccountAccessibility() {
        client.mutateWith(studentJwt())
                .patch()
                .uri("/api/v1/accounts/1/accessibility")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accessibility_extra_time", true))
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(accountService);
    }

    @Test
    void validatesAccessibilityPatchBodyForAdmin() {
        client.mutateWith(adminJwt())
                .patch()
                .uri("/api/v1/accounts/1/accessibility")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(accountService);
    }

    @Test
    void adminAccessibilityPatchBindsDocumentedPayloadAndCallsService() {
        AccountResponse response = new AccountResponse(1L, "student", "student@kixi.ao", true,
                true, true, null, null, null, null);
        when(accountService.updateAccessibility(eq(1L), any(AccountAccessibilityRequest.class)))
                .thenReturn(Mono.just(response));

        client.mutateWith(adminJwt())
                .patch()
                .uri("/api/v1/accounts/1/accessibility")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accessibility_extra_time", true))
                .exchange()
                .expectStatus().isOk();

        verify(accountService).updateAccessibility(eq(1L), eq(new AccountAccessibilityRequest(true)));
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
        when(ocrServiceClient.extractTextFromUploadedFiles(anyList())).thenReturn(Mono.just(successfulOcrResponse()));

        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", png(1, 1, 7))
                .filename("exam.png")
                .contentType(MediaType.IMAGE_PNG);

        client.mutateWith(teacherJwt())
                .post()
                .uri("/api/v1/ocr/extract/single")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .exchange()
                .expectStatus().isOk();

        verify(ocrServiceClient).extractTextFromUploadedFiles(anyList());
        verify(abuseRateLimiter).checkUpload(7L);
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
        body.part("files", png(1, 1, 7))
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
        verify(abuseRateLimiter).checkUpload(7L);
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

    @Test
    void theAccountListingFiltersByRoleWhenAsked() {
        // #147: the manager's enrollment picker only wants STUDENT accounts.
        when(accountService.findAllActiveWithRole("STUDENT")).thenReturn(Flux.empty());

        client.mutateWith(adminJwt())
                .get()
                .uri("/api/v1/accounts?role=STUDENT")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");

        verify(accountService).findAllActiveWithRole("STUDENT");
        org.mockito.Mockito.verify(accountService, org.mockito.Mockito.never()).findAllActive();
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
    void letsAnAnonymousBrowserLoadAQuestionFigureButNothingElseUnderUploads() {
        // The figure route is not in this slice, so an allowed request ends in 404; what matters is that it is not a 401.
        client.get().uri("/uploads/questions/7/figura.png").exchange().expectStatus().isNotFound();
        client.get().uri("/uploads/donations/3/enunciado.png").exchange().expectStatus().isUnauthorized();
        client.post().uri("/uploads/questions/7/figura.png").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void anImageUploadNeedsASession() {
        client.post().uri("/api/v1/images?kind=avatar").exchange().expectStatus().isUnauthorized();
        client.get().uri("/uploads/images/avatar/x.png").exchange().expectStatus().isNotFound();
    }

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

    @Test
    void aTeacherMayWriteTheTutorStyleButNothingElseOfAnAssignment() {
        // #206: the service checks that the assignment is the teacher's.
        when(teachingAssignmentService.updateTutorStyle(1L, "explica com exemplos", 7L, false))
                .thenReturn(Mono.just(new ao.creativemode.kixi.institutions.dto.assignment.TeachingAssignmentResponse(
                        1L, 3L, 2L, 3L, 4L, "explica com exemplos", null, null)));

        client.mutateWith(teacherJwt())
                .put()
                .uri("/api/v1/teaching-assignments/1/tutor-style")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("tutorStyle", "explica com exemplos"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.tutorStyle").isEqualTo("explica com exemplos");

        client.mutateWith(teacherJwt())
                .put()
                .uri("/api/v1/teaching-assignments/1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(assignmentPayload())
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void aStudentCannotWriteATutorStyle() {
        client.mutateWith(studentJwt())
                .put()
                .uri("/api/v1/teaching-assignments/1/tutor-style")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("tutorStyle", "x"))
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(teachingAssignmentService);
    }

    @Test
    void aTutorStyleOverTheLimitIsRejected() {
        client.mutateWith(teacherJwt())
                .put()
                .uri("/api/v1/teaching-assignments/1/tutor-style")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("tutorStyle", "x".repeat(101)))
                .exchange()
                .expectStatus().isBadRequest();

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
