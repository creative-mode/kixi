package ao.creativemode.kixi.simulations.community.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.simulations.community.dto.ShareSolutionRequest;
import ao.creativemode.kixi.simulations.community.dto.SolutionShareResponse;
import ao.creativemode.kixi.simulations.community.service.SolutionSharingService;
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
import reactor.core.publisher.Mono;

/**
 * {@code POST/DELETE /api/v1/simulations/{id}/share} over HTTP: the owner
 * opts in and back out, and nobody without a session flips the switch.
 *
 * <p>The student role reaching these routes is the point, not the setup: the
 * DELETE used to fall through to the staff-only catch-all, which would have
 * locked students out of revoking their own consent.
 */
@WebFluxTest(controllers = SolutionShareController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class SolutionShareControllerTest {

    private static final Long SIMULATION_ID = 50L;
    private static final Long STUDENT_ID = 12L;
    private static final Long TEACHER_ID = 7L;

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private SolutionSharingService solutionSharingService;

    @BeforeEach
    void setUp() {
        when(solutionSharingService.share(eq(SIMULATION_ID), anyLong(), any()))
                .thenReturn(Mono.just(receipt()));
        when(solutionSharingService.unshare(eq(SIMULATION_ID), anyLong()))
                .thenReturn(Mono.empty());
    }

    @Test
    void aStudentSharesAnonymously() {
        client.mutateWith(studentJwt())
                .post()
                .uri("/api/v1/simulations/" + SIMULATION_ID + "/share")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ShareSolutionRequest(true, null))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.simulationId").isEqualTo(SIMULATION_ID)
                .jsonPath("$.anonymous").isEqualTo(true);

        verify(solutionSharingService)
                .share(eq(SIMULATION_ID), eq(STUDENT_ID), any(ShareSolutionRequest.class));
    }

    @Test
    void aStudentTakesTheShareBack() {
        client.mutateWith(studentJwt())
                .delete()
                .uri("/api/v1/simulations/" + SIMULATION_ID + "/share")
                .exchange()
                .expectStatus().isNoContent();

        verify(solutionSharingService).unshare(SIMULATION_ID, STUDENT_ID);
    }

    @Test
    void aTeacherReachesTheRouteButOnlyForTheirOwnSimulation() {
        // Ownership lives in the service, not the role: the route lets staff
        // through and the service decides whose simulation it is.
        client.mutateWith(teacherJwt())
                .delete()
                .uri("/api/v1/simulations/" + SIMULATION_ID + "/share")
                .exchange()
                .expectStatus().isNoContent();

        verify(solutionSharingService).unshare(SIMULATION_ID, TEACHER_ID);
    }

    @Test
    void nobodyFlipsTheSwitchWithoutASession() {
        client.post()
                .uri("/api/v1/simulations/" + SIMULATION_ID + "/share")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ShareSolutionRequest(true, null))
                .exchange()
                .expectStatus().isUnauthorized();

        client.delete()
                .uri("/api/v1/simulations/" + SIMULATION_ID + "/share")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(solutionSharingService, never()).share(anyLong(), anyLong(), any());
        verify(solutionSharingService, never()).unshare(anyLong(), anyLong());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private static SolutionShareResponse receipt() {
        return new SolutionShareResponse(77L, SIMULATION_ID, true, null, LocalDateTime.now());
    }

    private static WebTestClientConfigurer studentJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(STUDENT_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }

    private static WebTestClientConfigurer teacherJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(TEACHER_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }
}
