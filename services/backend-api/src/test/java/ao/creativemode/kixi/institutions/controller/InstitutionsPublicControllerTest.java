package ao.creativemode.kixi.institutions.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.shared.exception.ApiAbuseRateLimitException;
import ao.creativemode.kixi.shared.exception.GlobalExceptionHandler;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.ApiAbuseRateLimiter;
import ao.creativemode.kixi.shared.config.TrustedProxyProperties;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The public institutions catalogue (kixi#597): anyone may call it, the listed rows are only id,
 * name and sigla of verified, non-deleted schools, and an enumerator is throttled by the same
 * limiter that guards login.
 */
@WebFluxTest(controllers = InstitutionsPublicController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class,
        GlobalExceptionHandler.class, TrustedProxyProperties.class})
class InstitutionsPublicControllerTest {

    @Autowired
    private WebTestClient client;

    @MockBean private JwtService jwtService;
    @MockBean private InstitutionRepository institutions;
    @MockBean private ApiAbuseRateLimiter abuseRateLimiter;

    @Test
    void theEndpointIsPublicAndCarriesMinimalPayload() {
        Institution i = new Institution();
        i.setId(7L);
        i.setName("Instituto de Telecomunicações");
        i.setShortName("ITEL");
        i.setVerified(true);

        when(institutions.findAllByVerifiedIsTrueAndDeletedAtIsNullOrderByNameAsc()).thenReturn(Flux.just(i));
        when(abuseRateLimiter.checkInstitutionsPublic(anyString())).thenReturn(Mono.empty());

        client.get().uri("/api/v1/institutions/public")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    org.assertj.core.api.Assertions.assertThat(body).contains("7");
                    org.assertj.core.api.Assertions.assertThat(body).contains("Instituto de Telecomunicações");
                    org.assertj.core.api.Assertions.assertThat(body).contains("ITEL");
                    // No verified timestamp, code, logo or any field beyond the three intended.
                    org.assertj.core.api.Assertions.assertThat(body).doesNotContain("verifiedAt");
                    org.assertj.core.api.Assertions.assertThat(body).doesNotContain("logo");
                    org.assertj.core.api.Assertions.assertThat(body).doesNotContain("code");
                });
    }

    @Test
    void theRateLimiterRefusesAbuseWithTooManyRequests() {
        // The flux argument of .then() is evaluated eagerly by the controller; it must exist even
        // though the rate-limit error means it will never be subscribed.
        when(institutions.findAllByVerifiedIsTrueAndDeletedAtIsNullOrderByNameAsc()).thenReturn(Flux.empty());
        when(abuseRateLimiter.checkInstitutionsPublic(anyString()))
                .thenReturn(Mono.error(new ApiAbuseRateLimitException("institutions-public", 60)));

        client.get().uri("/api/v1/institutions/public")
                .exchange()
                .expectBody(String.class)
                .value(body -> org.assertj.core.api.Assertions.assertThat(body).contains("429"));
    }
}
