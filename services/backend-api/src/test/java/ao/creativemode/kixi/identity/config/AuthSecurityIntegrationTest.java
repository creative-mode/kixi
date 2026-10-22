package ao.creativemode.kixi.identity.config;

import ao.creativemode.kixi.identity.controller.AuthController;
import ao.creativemode.kixi.identity.config.GoogleOAuth2Properties;
import ao.creativemode.kixi.identity.dto.auth.GoogleLinkAuthorizationRequest;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.AuthService;
import ao.creativemode.kixi.identity.service.GoogleLinkStateService;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.identity.service.LoginRateLimiter;
import ao.creativemode.kixi.identity.service.RegistrationRateLimiter;
import ao.creativemode.kixi.shared.config.TrustedProxyProperties;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.ApiAbuseRateLimiter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(controllers = AuthController.class)
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        JwtAuthenticationFilter.class, RequestIdWebFilter.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
class AuthSecurityIntegrationTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private AuthService authService;

    @MockBean
    private GoogleOAuth2Properties googleOAuth2Properties;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private RegistrationRateLimiter registrationRateLimiter;

    @MockBean
    private LoginRateLimiter loginRateLimiter;

    @MockBean
    private TrustedProxyProperties trustedProxies;

    @MockBean
    private ApiAbuseRateLimiter abuseRateLimiter;

    @MockBean
    private CurrentAccountService currentAccountService;

    @MockBean
    private GoogleLinkStateService googleLinkStateService;

    @Test
    void googleLinkAuthorizationRequiresBearerAuthenticationAtSecurityBoundary() {
        client.post()
                .uri("/api/v1/auth/google/link/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new GoogleLinkAuthorizationRequest("password"))
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
