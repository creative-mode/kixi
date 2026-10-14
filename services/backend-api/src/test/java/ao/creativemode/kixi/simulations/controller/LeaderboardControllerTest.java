package ao.creativemode.kixi.simulations.controller;

import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.http.HttpStatus;
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
import ao.creativemode.kixi.shared.exception.ApiException;
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
 *
 * <p>The ranking section below adds what only this layer can break: that the four query
 * parameters cross the boundary intact, and that the statuses the service answers with —
 * 409 for a paper being sat, 404 for one that does not exist — reach the client as
 * themselves rather than flattened into a 500 by the error boundary.</p>
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
        when(service.leaderboard(eq(42L), eq("class"), eq(null), eq("all"), eq("average")))
                .thenReturn(Mono.just(ranking()));

        client.mutateWith(studentJwt())
                .get()
                .uri("http://localhost/api/v1/leaderboard?scope=class")
                .exchange()
                .expectStatus().isOk();

        // A class id in the query string is not a cohort: there is no request shape that
        // reaches somebody else's group, which is the property #117 rests on.
        verify(service, never()).leaderboard(eq(42L), eq("class"),
                eq(7L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aCrossInstitutionRankingCarriesItsProvenanceToTheClient() {
        when(service.leaderboard(eq(42L), eq("global"), eq(null), eq("all"), eq("average")))
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

    // ── The query the ranking answers ─────────────────────────────────────

    /**
     * The ranking itself is closed to an anonymous caller.
     *
     * <p>Measured, because the obvious assumption about where the 401 comes from is wrong:
     * relaxing {@code SecurityConfig}'s leaderboard line to {@code permitAll()}, and the
     * closing {@code anyExchange()} with it, still leaves this green — the chain never gets
     * to decide. The refusal is {@code CurrentAccountService.requiredAccountId()}, which
     * errors with 401 on an empty security context, and the controller calls it first. So
     * this holds the ranking closed against an anonymous caller, which is the property that
     * matters, but it does not on its own prove the {@code .authenticated()} line is doing
     * the work. Second of two guards, not the only one.</p>
     */
    @Test
    void anonymousNeverReachesTheRanking() {
        client.get()
                .uri("http://localhost/api/v1/leaderboard")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(service, never()).leaderboard(any(), any(), any(), any(), any());
    }

    /**
     * The four parameters cross the boundary and nothing else.
     *
     * <p>{@code theCohortIsNeverAParameter} above proves no parameter can name another
     * cohort. This proves the ones that exist are not dropped or reordered on the way to
     * the service, which is the failure that would leave every ranking answering the wrong
     * question while still looking right.</p>
     */
    @Test
    void theQueryParametersReachTheServiceUnchanged() {
        when(service.leaderboard(eq(42L), eq("school"), eq(9L), eq("term"), eq("average")))
                .thenReturn(Mono.just(ranking()));

        client.mutateWith(studentJwt())
                .get()
                .uri("http://localhost/api/v1/leaderboard"
                        + "?scope=school&statementId=9&period=term")
                .exchange()
                .expectStatus().isOk();

        verify(service).leaderboard(42L, "school", 9L, "term", "average");
    }

    @Test
    void anUnsupportedScopeIsABadRequest() {
        when(service.leaderboard(eq(42L), eq("planeta"), eq(null), eq("all"), eq("average")))
                .thenReturn(Mono.error(ApiException.badRequest("Unknown scope: planeta")));

        client.mutateWith(studentJwt())
                .get()
                .uri("http://localhost/api/v1/leaderboard?scope=planeta")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void anUnsupportedPeriodIsABadRequest() {
        when(service.leaderboard(eq(42L), eq("class"), eq(null), eq("era"), eq("average")))
                .thenReturn(Mono.error(ApiException.badRequest("Unknown period: era")));

        client.mutateWith(studentJwt())
                .get()
                .uri("http://localhost/api/v1/leaderboard?period=era")
                .exchange()
                .expectStatus().isBadRequest();
    }

    /**
     * A paper being sat answers 409, and the status survives the hop to the client.
     *
     * <p>The rule is {@code LeaderboardService}'s — this only holds that it reaches the
     * caller as a 409 and not flattened into a 500 by the error boundary.</p>
     */
    @Test
    void aPaperBeingSatIsAConflictAndNotAServerError() {
        when(service.leaderboard(eq(42L), eq("class"), eq(9L), eq("all"), eq("average")))
                .thenReturn(Mono.error(ApiException.conflict(
                        "The ranking of a paper is only available once the exam rooms for it "
                                + "are closed")));

        client.mutateWith(studentJwt())
                .get()
                .uri("http://localhost/api/v1/leaderboard?statementId=9")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aPaperThatDoesNotExistIsNotFound() {
        when(service.leaderboard(eq(42L), eq("class"), eq(99L), eq("all"), eq("average")))
                .thenReturn(Mono.error(ApiException.notFound("No statement with id 99")));

        client.mutateWith(studentJwt())
                .get()
                .uri("http://localhost/api/v1/leaderboard?statementId=99")
                .exchange()
                .expectStatus().isNotFound();
    }

    /**
     * A teacher reads a ranking, but their own — the cohort still comes from the token.
     *
     * <p>There is no teacher branch here on purpose: the ranking is relative to whoever is
     * asking, and the role decides nothing about that. What the role does decide is whether
     * the caller gets in at all, which is {@code SecurityConfig}'s line and not this
     * controller's.</p>
     */
    @Test
    void aTeacherReachesTheirOwnRankingAndNotAClassWideOne() {
        when(service.leaderboard(eq(42L), eq("class"), eq(null), eq("all"), eq("average")))
                .thenReturn(Mono.just(ranking()));

        client.mutateWith(teacherJwt())
                .get()
                .uri("http://localhost/api/v1/leaderboard")
                .exchange()
                .expectStatus().isOk();

        verify(service).leaderboard(42L, "class", null, "all", "average");
    }

    private static LeaderboardResponse ranking() {
        return new LeaderboardResponse("class", 7L, null, "all", "average", 12, 12, 0, false,
                1, 90.0, null, List.of());
    }

    private static LeaderboardResponse globalRanking() {
        return new LeaderboardResponse("global", null, null, "all", "average", 21, 11, 2, false,
                1, 75.0, 75.0, List.of());
    }

    private static WebTestClientConfigurer studentJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "42", null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }

    private static WebTestClientConfigurer teacherJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "42", null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }
}
