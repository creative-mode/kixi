package ao.creativemode.kixi.simulations.community.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.dto.PageResponse;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.simulations.community.dto.CommunityStatsResponse;
import ao.creativemode.kixi.simulations.community.dto.SharedSolutionResponse;
import ao.creativemode.kixi.simulations.community.service.CommunityStatsService;
import ao.creativemode.kixi.simulations.community.service.SolutionSharingService;
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
 * {@code GET /api/v1/statements/{id}/community*} over HTTP: a student reads
 * the community aggregates and the shared resolutions, and nobody without a
 * session reads anything.
 *
 * <p>The services are mocked, so what is pinned here is the controller's
 * part: the account reaches the service, paging lands on the response, and
 * the anonymous get nothing at all.
 */
@WebFluxTest(controllers = StatementCommunityController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class StatementCommunityControllerTest {

    private static final Long STATEMENT_ID = 10L;
    private static final Long STUDENT_ID = 12L;

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private CommunityStatsService communityStatsService;

    @MockBean
    private SolutionSharingService solutionSharingService;

    @BeforeEach
    void setUp() {
        when(communityStatsService.findCommunity(eq(STATEMENT_ID), anyLong()))
                .thenReturn(Mono.just(stats()));
        when(solutionSharingService.listSolutions(eq(STATEMENT_ID), isNull(), anyInt(), anyInt(),
                        anyLong()))
                .thenReturn(Mono.just(PageResponse.of(List.of(solution()), 0, 20, 1)));
    }

    @Test
    void aStudentSeesTheCommunityAndTheirComparison() {
        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/statements/" + STATEMENT_ID + "/community")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.statementId").isEqualTo(STATEMENT_ID)
                .jsonPath("$.sampleSize").isEqualTo(3)
                .jsonPath("$.mine.simulationId").isEqualTo(50);

        verify(communityStatsService).findCommunity(STATEMENT_ID, STUDENT_ID);
    }

    @Test
    void aStudentPagesTheSharedResolutions() {
        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/statements/" + STATEMENT_ID + "/community/solutions?page=0&size=20")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalElements").isEqualTo(1)
                .jsonPath("$.content[0].authorName").isEqualTo("Ana")
                .jsonPath("$.content[0].institution.code").isEqualTo("ITEL");

        verify(solutionSharingService).listSolutions(STATEMENT_ID, null, 0, 20, STUDENT_ID);
    }

    @Test
    void nobodyReadsTheCommunityWithoutASession() {
        client.get()
                .uri("/api/v1/statements/" + STATEMENT_ID + "/community")
                .exchange()
                .expectStatus().isUnauthorized();

        client.get()
                .uri("/api/v1/statements/" + STATEMENT_ID + "/community/solutions")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(communityStatsService, never()).findCommunity(anyLong(), anyLong());
        verify(solutionSharingService, never())
                .listSolutions(anyLong(), isNull(), anyInt(), anyInt(), anyLong());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private static CommunityStatsResponse stats() {
        return new CommunityStatsResponse(STATEMENT_ID, 3, 12.0, 12.0,
                List.of(new CommunityStatsResponse.ScoreBucket(2, 8.0, 12.0, 1)),
                List.of(), 750.0, 2,
                new CommunityStatsResponse.MineSummary(50L, 14.0));
    }

    private static SharedSolutionResponse solution() {
        return new SharedSolutionResponse(1L,
                new SharedSolutionResponse.InstitutionRef(5L, "ITEL"), "Ana", 14.0, 900,
                List.of(new SharedSolutionResponse.SharedAnswer(20L, 1, "B", null, 5.0f)));
    }

    private static WebTestClientConfigurer studentJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(STUDENT_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }
}
