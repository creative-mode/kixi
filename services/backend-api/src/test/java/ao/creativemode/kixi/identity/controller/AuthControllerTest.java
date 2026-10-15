package ao.creativemode.kixi.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.exception.LoginRateLimitException;
import ao.creativemode.kixi.identity.dto.auth.LoginRequest;
import ao.creativemode.kixi.identity.config.GoogleOAuth2Properties;
import ao.creativemode.kixi.identity.dto.auth.LoginResponse;
import ao.creativemode.kixi.identity.dto.auth.RegisterRequest;
import ao.creativemode.kixi.identity.service.AuthService;
import ao.creativemode.kixi.identity.service.LoginRateLimiter;
import ao.creativemode.kixi.identity.service.RegistrationRateLimiter;
import ao.creativemode.kixi.shared.config.TrustedProxyProperties;
import ao.creativemode.kixi.shared.service.ApiAbuseRateLimiter;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuthControllerTest {

    private AuthService authService;
    private GoogleOAuth2Properties googleProperties;
    private RegistrationRateLimiter registrationRateLimiter;
    private LoginRateLimiter loginRateLimiter;
    private TrustedProxyProperties trustedProxies;
    private ApiAbuseRateLimiter abuseRateLimiter;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        googleProperties = new GoogleOAuth2Properties();
        googleProperties.setClientId("google-client");
        googleProperties.setRedirectUri("https://api.example.test/api/v1/auth/google/callback");
        registrationRateLimiter = mock(RegistrationRateLimiter.class);
        abuseRateLimiter = mock(ApiAbuseRateLimiter.class);
        when(registrationRateLimiter.check(anyString())).thenReturn(Mono.empty());
        loginRateLimiter = mock(LoginRateLimiter.class);
        when(loginRateLimiter.checkIdentity(anyString())).thenReturn(Mono.empty());
        when(loginRateLimiter.checkAddress(anyString())).thenReturn(Mono.empty());
        trustedProxies = new TrustedProxyProperties();
        when(abuseRateLimiter.checkLogin(anyString())).thenReturn(Mono.empty());
        controller = new AuthController(
                authService, googleProperties, registrationRateLimiter, loginRateLimiter,
                trustedProxies, abuseRateLimiter);
    }

    @Test
    void redirectPersistsStateInHttpOnlyLaxCookie() {
        ResponseEntity<Void> response = controller.googleRedirect(null).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getFirst("Location"))
                .contains("state=");
        assertThat(response.getHeaders().getFirst("Set-Cookie"))
                .contains("kixi_oauth_state=")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=/api/v1/auth/google");
    }

    @Test
    void callbackRejectsMissingOrMismatchedStateBeforeCallingGoogle() {
        StepVerifier.create(controller.googleCallback("code", "received", "stored", null))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(400);
                })
                .verify();

        verify(authService, never()).loginWithGoogle("code");
    }

    @Test
    void callbackClearsStateCookieAfterSuccessfulExchange() {
        LoginResponse loginResponse = new LoginResponse(
                "token",
                LoginResponse.TOKEN_TYPE,
                Instant.now(),
                42L,
                List.of("STUDENT")
        );
        org.mockito.Mockito.when(authService.loginWithGoogle("code"))
                .thenReturn(Mono.just(loginResponse));

        ResponseEntity<LoginResponse> response = controller
                .googleCallback("code", "same-state", "same-state", null)
                .block();

        assertThat(response).isNotNull();
        assertThat(response.getBody()).isEqualTo(loginResponse);
        assertThat(response.getHeaders().getFirst("Set-Cookie"))
                .contains("kixi_oauth_state=")
                .contains("Max-Age=0");
        verify(abuseRateLimiter).checkLogin("unknown");
    }

    @Test
    void registerReturnsCreatedWithLoginResponse() {
        RegisterRequest request = new RegisterRequest(
                "student", "student@kixi.ao", "password123", "Ana", "Silva");
        LoginResponse loginResponse = new LoginResponse(
                "token", LoginResponse.TOKEN_TYPE, Instant.now(), 9L, List.of("STUDENT"));
        org.mockito.Mockito.when(authService.register(request)).thenReturn(Mono.just(loginResponse));

        ResponseEntity<LoginResponse> response = controller.register(null, request).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isEqualTo(loginResponse);
        verify(authService).register(request);
        verify(registrationRateLimiter).check("unknown");
    }

    @Test
    void loginChecksAbuseLimitsBeforeAuthenticating() {
        LoginResponse loginResponse = new LoginResponse(
                "token", LoginResponse.TOKEN_TYPE, Instant.now(), 42L, List.of("STUDENT"));
        org.mockito.Mockito.when(authService.login("student@example.test", "password123"))
                .thenReturn(Mono.just(loginResponse));

        ResponseEntity<LoginResponse> response = controller.login(
                null, new LoginRequest("student@example.test", "password123")).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // The login path goes through LoginRateLimiter, which counts the address and the
        // identity separately (#240). ApiAbuseRateLimiter.checkLogin was dropped from it:
        // two limiters in series on one door, with different windows, means the tighter
        // one silently wins and the other is paid for on every request.
        verify(loginRateLimiter).checkAddress("login-ip:unknown");
        verify(loginRateLimiter).checkIdentity("login-identity:student@example.test");
        verify(authService).login("student@example.test", "password123");
    }

    @Test
    void googleRedirectChecksAbuseLimitBeforeRedirecting() {
        ResponseEntity<Void> response = controller.googleRedirect(null).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(302);
        verify(abuseRateLimiter).checkLogin("unknown");
    }

    @Test
    void registerRejectsInvalidPayloadBeforeCallingService() {
        WebTestClient client = WebTestClient.bindToController(controller).build();

        client.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "username": "student",
                          "email": "not-an-email",
                          "password": "short",
                          "firstName": "",
                          "lastName": "Silva"
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest();

        verify(authService, never()).register(org.mockito.ArgumentMatchers.any(RegisterRequest.class));
    }

    // ── The login window (issue #123) ─────────────────────────────────────

    /**
     * Both keys are counted, and the identity one is normalised.
     *
     * <p>Two because either alone is half a defence. The address stops one host spraying
     * usernames; the identity stops many hosts converging on one account. Normalised
     * because {@code Student@Kixi.AO} and {@code student@kixi.ao} are the same account,
     * and a limiter that counted them as two would be walked around by changing case.</p>
     */
    @Test
    void loginCountsTheAttemptAgainstTheAddressAndTheIdentity() {
        LoginResponse response = new LoginResponse("token", "Bearer", Instant.now(), 1L, List.of("STUDENT"));
        when(authService.login("  Student@Kixi.AO  ", "secret")).thenReturn(Mono.just(response));

        controller.login(null, new LoginRequest("  Student@Kixi.AO  ", "secret")).block();

        verify(loginRateLimiter).checkIdentity("login-identity:student@kixi.ao");
        verify(loginRateLimiter).checkAddress(startsWith("login-ip:"));
        // The controller normalises the rate-limit key, not the credential lookup:
        // AuthService already folds case when it finds the account.
        verify(authService).login("  Student@Kixi.AO  ", "secret");
    }

    /**
     * A refused attempt never reaches the credential check.
     *
     * <p>The point of the gate is that the expensive, guessable part is never reached
     * once the window is gone, so this asserts the password is not even offered.</p>
     */
    @Test
    void aRefusedLoginNeverReachesTheCredentialCheck() {
        when(loginRateLimiter.checkIdentity(startsWith("login-identity:")))
                .thenReturn(Mono.error(new LoginRateLimitException(42L)));

        StepVerifier.create(controller.login(null, new LoginRequest("student", "guess")))
                .expectErrorSatisfies(error ->
                        assertThat(((ApiException) error).getStatusCode()).isEqualTo(429))
                .verify();

        verify(authService, never()).login(anyString(), anyString());
    }

    /**
     * The two keys are distinct rows, not one counter counted twice.
     *
     * <p>If they collided, an attacker could burn a victim's address budget with ten
     * attempts of their own, and the victim would find the account already throttled
     * without having tried anything.</p>
     */
    @Test
    void theAddressAndTheIdentityAreCountedSeparately() {
        LoginResponse response = new LoginResponse("token", "Bearer", Instant.now(), 1L, List.of("STUDENT"));
        when(authService.login(anyString(), anyString())).thenReturn(Mono.just(response));

        controller.login(null, new LoginRequest("someone", "secret")).block();

        ArgumentCaptor<String> identities = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> addresses = ArgumentCaptor.forClass(String.class);
        verify(loginRateLimiter, times(1)).checkAddress(addresses.capture());
        verify(loginRateLimiter, times(1)).checkIdentity(identities.capture());

        // Different rows, so exhausting one does not spend the other. An attacker could
        // otherwise burn a victim's address budget without ever touching their account.
        assertThat(identities.getAllValues()).hasSize(1);
        assertThat(addresses.getAllValues()).hasSize(1);
        assertThat(identities.getAllValues().get(0))
                .isNotEqualTo(addresses.getAllValues().get(0))
                .startsWith("login-identity:");
        assertThat(addresses.getAllValues().get(0)).startsWith("login-ip:");
    }

    // ── Which address is counted (issue #123) ────────────────────────────

    /**
     * With no proxy named, a forwarded header is worth nothing.
     *
     * <p>{@code X-Forwarded-For} is written by whoever sends the request. Believing it
     * would let an attacker mint a fresh address per attempt and the address half of the
     * limiter would stop limiting anything.</p>
     */
    @Test
    void aForwardedHeaderFromAnUntrustedPeerIsIgnored() {
        when(authService.login(anyString(), anyString()))
                .thenReturn(Mono.just(new LoginResponse(
                        "token", "Bearer", Instant.now(), 1L, List.of("STUDENT"))));

        controller.login(from("203.0.113.7", "198.51.100.9"), new LoginRequest("aluno", "s")).block();

        verify(loginRateLimiter).checkAddress("login-ip:203.0.113.7");
    }

    /**
     * A named proxy is believed, so the real client is the one counted.
     *
     * <p>The opposite failure is worse than the first one: behind a proxy the address the
     * application sees is the proxy's, shared by everyone, and a login limiter counting a
     * whole school as one client locks the school out at once.</p>
     */
    @Test
    void aForwardedHeaderFromANamedProxyIsBelieved() {
        trustedProxies.setIps(List.of("203.0.113.7"));
        when(authService.login(anyString(), anyString()))
                .thenReturn(Mono.just(new LoginResponse(
                        "token", "Bearer", Instant.now(), 1L, List.of("STUDENT"))));

        controller.login(from("203.0.113.7", "198.51.100.9"), new LoginRequest("aluno", "s")).block();

        verify(loginRateLimiter).checkAddress("login-ip:198.51.100.9");
    }

    /** An empty header is not evidence of an address, so the peer stands. */
    @Test
    void anEmptyForwardedHeaderFallsBackToTheProxy() {
        trustedProxies.setIps(List.of("203.0.113.7"));
        when(authService.login(anyString(), anyString()))
                .thenReturn(Mono.just(new LoginResponse(
                        "token", "Bearer", Instant.now(), 1L, List.of("STUDENT"))));

        controller.login(from("203.0.113.7", "  "), new LoginRequest("aluno", "s")).block();

        verify(loginRateLimiter).checkAddress("login-ip:203.0.113.7");
    }

    private static ServerWebExchange from(String peer, String forwardedFor) {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .header("X-Forwarded-For", forwardedFor)
                .remoteAddress(new InetSocketAddress(peer, 51234))
                .build();
        return MockServerWebExchange.from(request);
    }
}
