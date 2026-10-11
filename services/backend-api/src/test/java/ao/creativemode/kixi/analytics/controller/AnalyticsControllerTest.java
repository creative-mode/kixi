package ao.creativemode.kixi.analytics.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.analytics.dto.Overview;
import ao.creativemode.kixi.analytics.service.AnalyticsService;
import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.util.List;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** /api/v1/analytics over HTTP with the real security chain. */
@WebFluxTest(controllers = AnalyticsController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class AnalyticsControllerTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private AnalyticsService service;

    @Test
    void aTeacherGetsTheOverviewInTheShapeTheDashboardReads() {
        when(service.overview()).thenReturn(Mono.just(new Overview(
                "TEACHER", 1, 2, 30, 1,
                new Overview.Statements(4, 3, 1, 0),
                new Overview.Simulations(10, 8, 1, 1, 9),
                72.5, null, 1800L)));

        client.mutateWith(as("TEACHER"))
                .get().uri("/api/v1/analytics/overview")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.scope").isEqualTo("TEACHER")
                .jsonPath("$.statements.missingAnswerKey").isEqualTo(0)
                .jsonPath("$.simulations.uniqueStudents").isEqualTo(9)
                .jsonPath("$.averageScorePercent").isEqualTo(72.5)
                // null stays null: "no data yet" is not 0
                .jsonPath("$.passRatePercent").isEmpty();
    }

    @Test
    void aStudentIsForbidden() {
        client.mutateWith(as("STUDENT"))
                .get().uri("/api/v1/analytics/overview")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void withoutASessionItIsUnauthorized() {
        client.get().uri("/api/v1/analytics/classes")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void anOutOfRangeParameterIsABadRequest() {
        when(service.activity(any())).thenThrow(ApiException.badRequest("days must be between 1 and 365"));

        client.mutateWith(as("ADMIN"))
                .get().uri("/api/v1/analytics/activity?days=999")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void anotherTeachersClassIsForbidden() {
        when(service.classStudents(5L)).thenReturn(Flux.error(
                ApiException.forbidden("Class is outside your teaching assignments")));

        client.mutateWith(as("TEACHER"))
                .get().uri("/api/v1/analytics/classes/5/students")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void listsComeBackAsArrays() {
        when(service.statements(any())).thenReturn(Flux.empty());

        client.mutateWith(as("ADMIN"))
                .get().uri("/api/v1/analytics/statements?limit=5")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");
    }

    private static WebTestClientConfigurer as(String role) {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "42", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
