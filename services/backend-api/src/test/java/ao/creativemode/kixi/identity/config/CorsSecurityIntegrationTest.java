package ao.creativemode.kixi.identity.config;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClientConfigurer;

import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.simulations.service.SimulationAnswerService;
import ao.creativemode.kixi.simulations.service.SimulationService;
import ao.creativemode.kixi.simulations.service.SimulationSubmissionService;
import reactor.core.publisher.Flux;

@WebFluxTest(controllers = {
        ao.creativemode.kixi.simulations.controller.SimulationController.class
})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000",
        "app.cors.allowed-origins=https://aluno.kixi.ao"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class CorsSecurityIntegrationTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private SimulationService simulationService;

    @MockBean
    private ao.creativemode.kixi.simulations.service.SimulationResultService simulationResultService;

    @MockBean
    private SimulationSubmissionService simulationSubmissionService;

    @MockBean
    private SimulationAnswerService simulationAnswerService;

    private static WebTestClientConfigurer studentJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "42", null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }

    @Test
    void preflightFromAllowedOriginIsAccepted() {
        client.mutateWith(studentJwt())
                .options()
                .uri("http://localhost/api/simulations")
                .header("Origin", "https://aluno.kixi.ao")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://aluno.kixi.ao");
    }

    @Test
    void actualRequestCarriesAllowOriginHeader() {
        when(simulationService.findAllActiveForAccount(42L)).thenReturn(Flux.empty());

        client.mutateWith(studentJwt())
                .get()
                .uri("http://localhost/api/simulations")
                .header("Origin", "https://aluno.kixi.ao")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://aluno.kixi.ao");
    }

    @Test
    void preflightForPatchEndpointIsAccepted() {
        client.mutateWith(studentJwt())
                .options()
                .uri("http://localhost/api/v1/statements/1/visibility")
                .header("Origin", "https://aluno.kixi.ao")
                .header("Access-Control-Request-Method", "PATCH")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://aluno.kixi.ao");
    }

    @Test
    void disallowedOriginIsRejectedWithoutAllowHeader() {
        EntityExchangeResult<byte[]> result = client.mutateWith(studentJwt())
                .get()
                .uri("http://localhost/api/simulations")
                .header("Origin", "https://evil.example.com")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(byte[].class)
                .returnResult();

        List<String> allowOrigin = result.getResponseHeaders().get("Access-Control-Allow-Origin");
        if (allowOrigin != null && allowOrigin.stream().anyMatch(v -> v.contains("evil"))) {
            throw new AssertionError("evil origin must not be echoed: " + allowOrigin);
        }
    }
}
