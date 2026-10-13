package ao.creativemode.kixi.simulations.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import java.util.List;
import java.util.Map;

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

import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.simulations.dto.leaderboard.LeaderboardConsentResponse;
import ao.creativemode.kixi.simulations.dto.leaderboard.LeaderboardResponse;
import ao.creativemode.kixi.simulations.service.LeaderboardService;

import reactor.core.publisher.Mono;

/**
 * The leaderboard endpoints over HTTP, with the real security chain (#117, #189).
 *
 * <p>The consent switch is the one that is worth the file. It is the only place in the
 * leaderboard where a body decides something about somebody's account, and the one way
 * a caller could name a student is by asking for their consent — so the account has to
 * come from the token, and a body with no answer in it has to be refused rather than
 * quietly read as a "no" or, worse, as a "yes".</p>
 */
@WebFluxTest(controllers = LeaderboardController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class LeaderboardControllerTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private LeaderboardService service;

    // ── The consent switch ───────────────────────────────────────────────

    @Test
    void anonymousCannotChangeTheConsent() {
        client.put()
                .uri("http://localhost/api/v1/leaderboard/consent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("crossInstitution", true))
                .exchange()
                .expectStatus().isUnauthorized();

        verify(service, never()).consent(42L, true);
    }

    @Test
    void theConsentIsTheCallersAndNotWhicheverAccountTheBodyNames() {
        when(service.consent(eq(42L), eq(true)))
                .thenReturn(Mono.just(new LeaderboardConsentResponse(true)));

        client.mutateWith(studentJwt())
                .put()
                .uri("http://localhost/api/v1/leaderboard/consent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("crossInstitution", true, "accountId", 99))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.crossInstitution").isEqualTo(true);

        verify(service).consent(42L, true);
    }

    @Test
    void takingTheConsentBackIsTheSameEndpoint() {
        when(service.consent(eq(42L), eq(false)))
                .thenReturn(Mono.just(new LeaderboardConsentResponse(false)));

        client.mutateWith(studentJwt())
                .put()
                .uri("http://localhost/api/v1/leaderboard/consent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("crossInstitution", false))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.crossInstitution").isEqualTo(false);
    }

    /**
     * An empty body is not consent, and not a refusal either.
     *
     * <p>Both readings would be wrong: the first publishes a student's performance to
     * strangers on the strength of a request that answered nothing, the second writes a
     * "no" the student never gave. Either way the caller is told nothing happened.</p>
     */
    @Test
    void aBodyWithoutAnAnswerIsRefusedRatherThanGuessedAt() {
        client.mutateWith(studentJwt())
                .put()
                .uri("http://localhost/api/v1/leaderboard/consent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isBadRequest();

        verify(service, never()).consent(eq(42L), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void aNullAnswerIsAlsoRefused() {
        // Sent as raw JSON because a null is the point: Map.of will not hold one.
        client.mutateWith(studentJwt())
                .put()
                .uri("http://localhost/api/v1/leaderboard/consent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"crossInstitution\": null}")
                .exchange()
                .expectStatus().isBadRequest();

        verify(service, never()).consent(eq(42L), org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ── The ranking itself ───────────────────────────────────────────────

    @Test
    void theCohortIsNeverAParameter() {
        when(service.leaderboard(eq(42L), eq("class"), eq(null), eq("all")))
                .thenReturn(Mono.just(ranking()));

        client.mutateWith(studentJwt())
                .get()
                .uri("http://localhost/api/v1/leaderboard?scope=class")
                .exchange()
                .expectStatus().isOk();

        // A class id in the query string is not a cohort: there is no request shape that
        // reaches somebody else's group, which is the property #117 rests on.
        verify(service, never()).leaderboard(eq(42L), eq("class"),
                eq(7L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aCrossInstitutionRankingCarriesItsProvenanceToTheClient() {
        when(service.leaderboard(eq(42L), eq("global"), eq(null), eq("all")))
                .thenReturn(Mono.just(globalRanking()));

        client.mutateWith(studentJwt())
                .get()
                .uri("http://localhost/api/v1/leaderboard?scope=global")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.scope").isEqualTo("global")
                .jsonPath("$.institutionsCounted").isEqualTo(2)
                .jsonPath("$.myPercentile").isEqualTo(75.0)
                // No group, so no group id.
                .jsonPath("$.groupId").doesNotExist();
    }

    private static LeaderboardResponse ranking() {
        return new LeaderboardResponse("class", 7L, null, "all", 12, 12, 0, false,
                1, 90.0, null, List.of());
    }

    private static LeaderboardResponse globalRanking() {
        return new LeaderboardResponse("global", null, null, "all", 21, 11, 2, false,
                1, 75.0, 75.0, List.of());
    }

    private static WebTestClientConfigurer studentJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "42", null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }
}
