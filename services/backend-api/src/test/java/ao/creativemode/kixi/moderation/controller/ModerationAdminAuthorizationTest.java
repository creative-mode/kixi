package ao.creativemode.kixi.moderation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.moderation.dto.AuditEntryResponse;
import ao.creativemode.kixi.moderation.dto.ModerationReportResponse;
import ao.creativemode.kixi.moderation.dto.ReportResponse;
import ao.creativemode.kixi.moderation.model.ModerationReport;
import ao.creativemode.kixi.moderation.repository.ModerationAdminRepository;
import ao.creativemode.kixi.moderation.repository.ModerationReportRepository;
import ao.creativemode.kixi.moderation.service.ModerationDecisionService;
import ao.creativemode.kixi.moderation.service.ModerationService;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.time.LocalDateTime;
import java.util.List;
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
 * A moderação do administrador pela cadeia de segurança real (kixi#591): o ADMIN lista, decide e consulta a auditoria;
 * o professor, o coordenador e o aluno recebem 403 com o motivo no corpo, e nada chega ao serviço. O que o aluno faz
 * (denunciar) continua aberto ao aluno.
 */
@WebFluxTest(controllers = {ModerationAdminController.class, ModerationController.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class, CurrentAccountService.class,
        JwtAuthenticationFilter.class, RequestIdWebFilter.class, ModerationDecisionService.class})
class ModerationAdminAuthorizationTest {

    private static final long ADMIN_ID = 1L;

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private ModerationService studentSide;

    @MockBean
    private ModerationReportRepository reports;

    @MockBean
    private ModerationAdminRepository admin;

    @BeforeEach
    void setUp() {
        ModerationReportResponse decided = new ModerationReportResponse(7L, "ACCOUNT", 42L, "Aluno", 9L, "Outro",
                "OTHER", "x", "SUSPENDED", LocalDateTime.now(), LocalDateTime.now(), "Admin", "nota da decisão");
        when(admin.queue(any())).thenReturn(Flux.just(decided));
        when(admin.audit()).thenReturn(Flux.just(new AuditEntryResponse(1L, LocalDateTime.now(), "Admin", "SUSPENDED",
                "ACCOUNT", 42L, "Aluno", 7L, "nota da decisão")));
        ModerationReport open = new ModerationReport();
        open.setId(7L);
        open.setTargetType("ACCOUNT");
        open.setTargetAccountId(42L);
        when(reports.findById(7L)).thenReturn(Mono.just(open));
        when(admin.closeIfOpen(eq(7L), anyString(), eq(ADMIN_ID), anyString(), any())).thenReturn(Mono.just(1L));
        when(admin.deactivateAccount(42L)).thenReturn(Mono.just(1L));
        when(admin.recordAudit(anyLong(), anyString(), anyString(), anyLong(), anyLong(), anyString(), any()))
                .thenReturn(Mono.empty());
        when(admin.report(7L)).thenReturn(Mono.just(decided));
        when(studentSide.reportAccount(anyLong(), anyLong(), any())).thenReturn(Mono.just(
                new ReportResponse(1L, "ACCOUNT", 42L, 5L, "OTHER", "x", LocalDateTime.now())));
    }

    @Test
    void anAdministratorListsTheQueueByStatus() {
        client.mutateWith(as(ADMIN_ID, "ROLE_ADMIN")).get().uri("/api/v1/moderation/reports?status=OPEN")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[0].id").isEqualTo(7).jsonPath("$[0].targetName").isEqualTo("Aluno");
        verify(admin).queue("OPEN");
    }

    @Test
    void anUnknownStatusIsABadRequest() {
        client.mutateWith(as(ADMIN_ID, "ROLE_ADMIN")).get().uri("/api/v1/moderation/reports?status=PENDING")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void anAdministratorReadsTheAudit() {
        client.mutateWith(as(ADMIN_ID, "ROLE_ADMIN")).get().uri("/api/v1/moderation/audit")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[0].reportId").isEqualTo(7).jsonPath("$[0].note").isEqualTo("nota da decisão");
    }

    @Test
    void anAdministratorSuspendsAnAccountAndTheDecisionIsAudited() {
        client.mutateWith(as(ADMIN_ID, "ROLE_ADMIN")).post().uri("/api/v1/moderation/reports/7/suspend")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"Faz-se passar por um professor.\"}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("SUSPENDED");
        verify(admin).deactivateAccount(42L);
        verify(admin).recordAudit(eq(7L), eq("SUSPENDED"), eq("ACCOUNT"), eq(42L), eq(ADMIN_ID),
                eq("Faz-se passar por um professor."), any());
    }

    @Test
    void everyOtherRoleIsForbiddenWithTheReasonInTheBody() {
        for (String role : List.of("ROLE_TEACHER", "ROLE_COORDINATOR", "ROLE_STUDENT")) {
            client.mutateWith(as(5L, role)).get().uri("/api/v1/moderation/reports")
                    .exchange().expectStatus().isForbidden()
                    .expectBody().jsonPath("$.detail").value(d -> org.assertj.core.api.Assertions
                            .assertThat((String) d).contains("administrador"));
            client.mutateWith(as(5L, role)).get().uri("/api/v1/moderation/audit")
                    .exchange().expectStatus().isForbidden()
                    .expectBody().jsonPath("$.title").isEqualTo("Forbidden");
            client.mutateWith(as(5L, role)).post().uri("/api/v1/moderation/reports/7/suspend")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"uma nota qualquer longa\"}")
                    .exchange().expectStatus().isForbidden();
            client.mutateWith(as(5L, role)).post().uri("/api/v1/moderation/reports/7/ignore")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"\"}")
                    .exchange().expectStatus().isForbidden();
        }
        verifyNoInteractions(admin);
        verify(reports, never()).findById(anyLong());
    }

    @Test
    void withoutASessionTheModerationIsUnauthorized() {
        client.get().uri("/api/v1/moderation/reports").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void aStudentStillReportsAnAccount() {
        client.mutateWith(as(5L, "ROLE_STUDENT")).post().uri("/api/v1/profiles/accounts/42/reports")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"reason\":\"OTHER\",\"note\":\"x\"}")
                .exchange().expectStatus().isCreated();
    }

    private static WebTestClientConfigurer as(long accountId, String role) {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(accountId), null, List.of(new SimpleGrantedAuthority(role))));
    }
}
