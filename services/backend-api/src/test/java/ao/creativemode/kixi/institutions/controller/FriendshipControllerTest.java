package ao.creativemode.kixi.institutions.controller;

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
import ao.creativemode.kixi.institutions.dto.friendship.ColleagueResponse;
import ao.creativemode.kixi.institutions.dto.friendship.FriendResponse;
import ao.creativemode.kixi.institutions.service.FriendshipService;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The friendship endpoints over HTTP, with the real security chain (issue #121).
 *
 * <p>Two things are worth testing here and neither is the service. First, that the
 * account always comes from the token: a friendship is the one relationship here where
 * the argument decides everything, so a request that named its own sender would be a
 * request anybody could send. Second, that the rule really is open to any signed-in
 * account — this file's neighbours exist because rules fall through each other.</p>
 */
@WebFluxTest(controllers = FriendshipController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class FriendshipControllerTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private FriendshipService service;

    // ── Who gets in ──────────────────────────────────────────────────────

    @Test
    void anonymousNeverReachesAFriendship() {
        client.get()
                .uri("http://localhost/api/v1/friends")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void anonymousCannotSendAFriendRequest() {
        client.post()
                .uri("http://localhost/api/v1/friends")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", 43))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** A teacher follows a colleague exactly like a student does. */
    @Test
    void aTeacherMayFollowAColleague() {
        when(service.request(eq(1L), any())).thenReturn(Mono.just(friend()));

        client.mutateWith(teacherJwt())
                .post()
                .uri("http://localhost/api/v1/friends")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", 43))
                .exchange()
                .expectStatus().isCreated();
    }

    // ── Where the account comes from ─────────────────────────────────────

    @Test
    void theFriendListIsTheCallersAndNotTheOnesAsked() {
        when(service.listFriends(42L)).thenReturn(Flux.just(friend()));

        client.mutateWith(studentJwt())
                .get()
                .uri("http://localhost/api/v1/friends")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].accountId").isEqualTo(43);

        // No parameter can widen it: the cohort is the caller's friends, not a list
        // somebody names.
        verify(service, never()).listFriends(43L);
    }

    @Test
    void aRequestCarriesOnlyTheColleagueNeverItsOwnSender() {
        when(service.request(eq(42L), any())).thenReturn(Mono.just(friend()));

        client.mutateWith(studentJwt())
                .post()
                .uri("http://localhost/api/v1/friends")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", 43, "requesterId", 99))
                .exchange()
                .expectStatus().isCreated();

        // The sender came from the token, so a "requesterId" in the body is ignored
        // rather than obeyed — the record has no field to put it in.
        verify(service).request(eq(42L), eq(new ao.creativemode.kixi.institutions.dto
                .friendship.FriendRequest(43L)));
    }

    @Test
    void acceptingAnswersARequestAddressedToTheCaller() {
        when(service.accept(42L, 5L)).thenReturn(Mono.just(friend()));

        client.mutateWith(studentJwt())
                .post()
                .uri("http://localhost/api/v1/friends/5/accept")
                .exchange()
                .expectStatus().isOk();

        verify(service).accept(42L, 5L);
    }

    @Test
    void removingIsScopedToTheCaller() {
        when(service.remove(42L, 5L)).thenReturn(Mono.empty());

        client.mutateWith(studentJwt())
                .delete()
                .uri("http://localhost/api/v1/friends/5")
                .exchange()
                .expectStatus().isNoContent();

        verify(service).remove(42L, 5L);
    }

    @Test
    void theSearchIsOverTheCallersOwnSchoolYear() {
        when(service.searchColleagues(eq(42L), any()))
                .thenReturn(Flux.just(new ColleagueResponse(43L, "Bruno S.")));

        client.mutateWith(studentJwt())
                .get()
                .uri("http://localhost/api/v1/friends/search?q=bruno")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].accountId").isEqualTo(43);
    }

    @Test
    void theInboxHoldsOnlyTheCallersRequests() {
        when(service.listRequests(42L)).thenReturn(Flux.just(friend()));

        client.mutateWith(studentJwt())
                .get()
                .uri("http://localhost/api/v1/friends/requests")
                .exchange()
                .expectStatus().isOk();
    }

    // ── The rule this PR added next to the feed ──────────────────────────

    /**
     * Students reach friendships and not the staff-only dashboard.
     *
     * <p>Both rules are in the same chain, and the merge that brought the social feed in
     * left its {@code .authenticated()} orphaned once. If this passes while the analytics
     * one does not, the fallback has widened again.</p>
     */
    @Test
    void aStudentReachesFriendshipsButNotTheDashboard() {
        when(service.listFriends(42L)).thenReturn(Flux.just(friend()));

        client.mutateWith(studentJwt())
                .get()
                .uri("http://localhost/api/v1/friends")
                .exchange()
                .expectStatus().isOk();

        client.mutateWith(studentJwt())
                .get()
                .uri("http://localhost/api/v1/analytics/overview")
                .exchange()
                .expectStatus().isForbidden();
    }

    private static FriendResponse friend() {
        return new FriendResponse(5L, 43L, "Bruno S.", "ACCEPTED", true, false);
    }

    private static WebTestClientConfigurer studentJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "42", null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }

    private static WebTestClientConfigurer teacherJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "1", null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }
}