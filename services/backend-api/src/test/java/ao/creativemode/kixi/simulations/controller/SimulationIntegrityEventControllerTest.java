package ao.creativemode.kixi.simulations.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import java.time.LocalDateTime;
import java.util.List;

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

import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.simulations.dto.integrity.SimulationIntegrityEventResponse;
import ao.creativemode.kixi.simulations.model.SimulationIntegrityEventKind;
import ao.creativemode.kixi.simulations.service.SimulationIntegrityEventService;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = SimulationIntegrityEventController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class SimulationIntegrityEventControllerTest {
    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private SimulationIntegrityEventService service;

    @Test
    void studentRecordsAnEventAndServerResponseIncludesItsTimestamp() {
        when(service.record(eq(50L), eq(12L), argThat(anyRequest()))).thenReturn(Mono.just(
                new SimulationIntegrityEventResponse(31L, 50L, SimulationIntegrityEventKind.TAB_HIDDEN,
                        LocalDateTime.of(2026, 10, 21, 10, 0), null)));

        client.mutateWith(studentJwt()).post().uri("/api/v1/simulations/50/integrity-events")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"kind\":\"TAB_HIDDEN\"}")
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.id").isEqualTo(31)
                .jsonPath("$.simulationId").isEqualTo(50)
                .jsonPath("$.kind").isEqualTo("TAB_HIDDEN")
                .jsonPath("$.at").isEqualTo("2026-10-21T10:00:00");
        verify(service).record(eq(50L), eq(12L), argThat(anyRequest()));
    }

    @Test
    void requiresAuthenticationAndRejectsUnknownKinds() {
        client.post().uri("/api/v1/simulations/50/integrity-events")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"kind\":\"UNKNOWN\"}")
                .exchange().expectStatus().isUnauthorized();

        client.mutateWith(studentJwt()).post().uri("/api/v1/simulations/50/integrity-events")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"kind\":\"UNKNOWN\"}")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void mapsOwnershipAndExpiredAttemptRejections() {
        when(service.record(eq(50L), eq(12L), argThat(anyRequest())))
                .thenReturn(Mono.error(ApiException.notFound("Simulation not found")))
                .thenReturn(Mono.error(ApiException.conflict("The time allowed for this simulation has expired")));

        client.mutateWith(studentJwt()).post().uri("/api/v1/simulations/50/integrity-events")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"kind\":\"TAB_HIDDEN\"}")
                .exchange().expectStatus().isNotFound();
        client.mutateWith(studentJwt()).post().uri("/api/v1/simulations/50/integrity-events")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"kind\":\"TAB_HIDDEN\"}")
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void rejectsMissingKindBeforeCallingTheService() {
        client.mutateWith(studentJwt()).post().uri("/api/v1/simulations/50/integrity-events")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isBadRequest();
    }

    private static org.mockito.ArgumentMatcher<ao.creativemode.kixi.simulations.dto.integrity.SimulationIntegrityEventRequest>
            anyRequest() {
        return request -> request != null && request.kind() == SimulationIntegrityEventKind.TAB_HIDDEN
                && request.questionId() == null;
    }

    private static org.springframework.test.web.reactive.server.WebTestClientConfigurer studentJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken("12", null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }
}
