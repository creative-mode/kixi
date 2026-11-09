package ao.creativemode.kixi.training.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClientConfigurer;
import reactor.core.publisher.Mono;

import ao.creativemode.kixi.simulations.dto.simulation.SimulationResponse;
import ao.creativemode.kixi.training.dto.MatrixPracticeRequest;
import ao.creativemode.kixi.training.dto.MatrixPracticeResponse;
import ao.creativemode.kixi.training.dto.TrainingPlanResponse;
import ao.creativemode.kixi.training.service.MatrixPracticeService;
import ao.creativemode.kixi.training.service.TrainingPlanService;
import java.time.LocalDateTime;

/** O plano de treino e o treino por temas por HTTP (kixi#729). */
@WebFluxTest(controllers = TrainingController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class TrainingControllerTest {

    private static final long STUDENT = 12L;
    private static final long ROOM = 40L;

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private TrainingPlanService plans;

    @MockBean
    private MatrixPracticeService practice;

    @Test
    void theStudentReadsThePlan() {
        when(plans.plan(ROOM, STUDENT)).thenReturn(Mono.just(new TrainingPlanResponse(ROOM, 3L, "Matemática", "P1",
                LocalDateTime.of(2026, 12, 1, 8, 0), 90, 20.0, List.of(new TrainingPlanResponse.Item(5L, "Funções", 3, 8.0,
                        "KNOWLEDGE", 6, List.of(new TrainingPlanResponse.Source(77L, "P1 2024", 2024, 6)), 2, 1)))));

        client.mutateWith(as(STUDENT, "STUDENT")).get().uri("/api/v1/training/rooms/" + ROOM + "/plan")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.subjectName").isEqualTo("Matemática")
                .jsonPath("$.durationMinutes").isEqualTo(90)
                .jsonPath("$.items[0].available").isEqualTo(6)
                .jsonPath("$.items[0].sources[0].year").isEqualTo(2024)
                .jsonPath("$.items[0].attempted").isEqualTo(2)
                .jsonPath("$.items[0].correct").isEqualTo(1);
    }

    @Test
    void noMatrixOrNotInvitedIs404() {
        when(plans.plan(ROOM, STUDENT)).thenReturn(Mono.error(ApiException.notFound("Esta prova ainda não tem matriz.")));
        client.mutateWith(as(STUDENT, "STUDENT")).get().uri("/api/v1/training/rooms/" + ROOM + "/plan")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void thePlanNeedsASession() {
        client.get().uri("/api/v1/training/rooms/" + ROOM + "/plan").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void buildingAPracticeReturns201WithTheAllocation() {
        SimulationResponse simulation = new SimulationResponse(90L, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        when(practice.start(eq(STUDENT), any(MatrixPracticeRequest.class))).thenReturn(Mono.just(new MatrixPracticeResponse(
                simulation, List.of(new MatrixPracticeResponse.Allocation(5L, 4, 3)))));

        client.mutateWith(as(STUDENT, "STUDENT")).post().uri("/api/v1/simulations/practice/matrix")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"roomId\":40,\"items\":[{\"topicId\":5,\"count\":4}],\"durationMinutes\":45}")
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.simulation.id").isEqualTo(90)
                .jsonPath("$.allocation[0].topicId").isEqualTo(5)
                .jsonPath("$.allocation[0].requested").isEqualTo(4)
                .jsonPath("$.allocation[0].provided").isEqualTo(3);

        verify(practice).start(eq(STUDENT), eq(new MatrixPracticeRequest(ROOM,
                List.of(new MatrixPracticeRequest.Item(5L, 4)), 45)));
    }

    @Test
    void zeroProvidedIs400() {
        when(practice.start(eq(STUDENT), any())).thenReturn(Mono.error(ApiException.badRequest(
                "Não há questões disponíveis para os temas escolhidos.")));
        client.mutateWith(as(STUDENT, "STUDENT")).post().uri("/api/v1/simulations/practice/matrix")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"roomId\":40,\"items\":[{\"topicId\":5,\"count\":4}]}")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void aBadRequestBodyNeverReachesTheService() {
        for (String body : new String[] {
                "{\"items\":[{\"topicId\":5,\"count\":4}]}",
                "{\"roomId\":40,\"items\":[]}",
                "{\"roomId\":40,\"items\":[{\"topicId\":5,\"count\":0}]}",
                "{\"roomId\":40,\"items\":[{\"topicId\":5,\"count\":4}],\"durationMinutes\":0}"}) {
            client.mutateWith(as(STUDENT, "STUDENT")).post().uri("/api/v1/simulations/practice/matrix")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isBadRequest();
        }
        verify(practice, never()).start(anyLong(), any());
    }

    private static WebTestClientConfigurer as(long id, String role) {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(id), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
