package ao.creativemode.kixi.simulations.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.simulations.dto.simulationresult.SimulationResultResponse;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import ao.creativemode.kixi.simulations.service.SimulationResultService;
import ao.creativemode.kixi.simulations.service.SimulationService;
import java.time.LocalDateTime;
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
 * {@code GET /api/v1/simulations/{id}/result} over HTTP — the acceptance
 * criterion of #106, in the shape the issue words it: a student asks, and no
 * correct-answer field comes back until the simulation is finished.
 *
 * <p>The service is mocked, so what is pinned here is the controller's part: the
 * account reaches the service, the status maps to the right code, and a student
 * without a session gets nothing at all. Whether the key is withheld lives in
 * {@code SimulationResultServiceTest}.
 */
@WebFluxTest(controllers = SimulationController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class SimulationResultControllerTest {

    private static final Long TEACHER_ID = 7L;
    private static final Long STUDENT_ID = 12L;
    private static final Long SIMULATION_ID = 50L;

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private SimulationService simulationService;

    @MockBean
    private SimulationResultService simulationResultService;

    @MockBean
    private SimulationRepository simulations;

    @MockBean
    private SimulationAnswerRepository answers;

    @MockBean
    private QuestionRepository questions;

    @MockBean
    private QuestionOptionRepository options;

    @BeforeEach
    void setUp() {
        when(simulationResultService.findResult(eq(SIMULATION_ID), anyLong(), anyBoolean()))
                .thenReturn(Mono.just(result()));
    }

    @Test
    void aStudentGetsTheirResult() {
        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/simulations/" + SIMULATION_ID + "/result")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.simulationId").isEqualTo(SIMULATION_ID)
                .jsonPath("$.status").isEqualTo("FINISHED")
                .jsonPath("$.questions[0].correctOptionId").isEqualTo(31L);

        // Their own account, and not flagged as staff.
        verify(simulationResultService).findResult(SIMULATION_ID, STUDENT_ID, false);
    }

    @Test
    void aStudentStillRunningIsToldTheResultIsNotThereYet() {
        when(simulationResultService.findResult(SIMULATION_ID, STUDENT_ID, false))
                .thenReturn(Mono.error(ao.creativemode.kixi.shared.exception.ApiException.conflict(
                    "The result is only available once the simulation is finished; "
                        + "this one is IN_PROGRESS")));

        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/simulations/" + SIMULATION_ID + "/result")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.detail").value(
                    org.hamcrest.Matchers.containsString("finished"));
    }

    @Test
    void somebodyElsesSimulationIsNotFound() {
        when(simulationResultService.findResult(SIMULATION_ID, STUDENT_ID, false))
                .thenReturn(Mono.error(ao.creativemode.kixi.shared.exception.ApiException.notFound(
                    "Simulation not found: " + SIMULATION_ID)));

        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/simulations/" + SIMULATION_ID + "/result")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void staffAskWithoutBeingToldWhoseSimulationItIs() {
        client.mutateWith(teacherJwt())
                .get()
                .uri("/api/v1/simulations/" + SIMULATION_ID + "/result")
                .exchange()
                .expectStatus().isOk();

        verify(simulationResultService).findResult(SIMULATION_ID, TEACHER_ID, true);
    }

    @Test
    void nobodyAsksForAResultWithoutASession() {
        client.get()
                .uri("/api/v1/simulations/" + SIMULATION_ID + "/result")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(simulationResultService, never())
                .findResult(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void theRouteIsAlsoOnTheLegacyPrefix() {
        client.mutateWith(studentJwt())
                .get()
                .uri("/api/simulations/" + SIMULATION_ID + "/result")
                .exchange()
                .expectStatus().isOk();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private SimulationResultResponse result() {
        return new SimulationResultResponse(
                SIMULATION_ID,
                SimulationStatus.FINISHED,
                5.0,
                1800,
                LocalDateTime.now(),
                List.of(new SimulationResultResponse.QuestionResult(
                        20L, 1, "Qual?", "multiple_choice", 5.0, "because B is right",
                        31L, 30L, null, 0.0f,
                        List.of(
                            new SimulationResultResponse.OptionResult(30L, "A", "A text", false),
                            new SimulationResultResponse.OptionResult(31L, "B", "B text", true)))));
    }

    private static WebTestClientConfigurer teacherJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(TEACHER_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }

    private static WebTestClientConfigurer studentJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(STUDENT_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }
}