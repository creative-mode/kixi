package ao.creativemode.kixi.config;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.client.OcrServiceClient;
import ao.creativemode.kixi.controller.OcrController;
import ao.creativemode.kixi.dto.ocr.OcrResponse;
import ao.creativemode.kixi.model.Statement;
import ao.creativemode.kixi.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.service.CurrentAccountService;
import ao.creativemode.kixi.service.JwtService;
import ao.creativemode.kixi.service.OcrPersistenceService;
import ao.creativemode.kixi.service.OcrPersistenceService.StatementWithRelations;
import ao.creativemode.kixi.service.SimulationAnswerService;
import ao.creativemode.kixi.service.SimulationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClientConfigurer;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = {
        ao.creativemode.kixi.controller.SimulationController.class,
        ao.creativemode.kixi.controller.SimulationAnswerController.class,
        OcrController.class
})
@Import({SecurityConfig.class, CurrentAccountService.class, JwtAuthenticationFilter.class})
class AuthorizationIntegrationTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private SimulationService simulationService;

    @MockBean
    private SimulationAnswerService simulationAnswerService;

    @MockBean
    private OcrServiceClient ocrServiceClient;

    @MockBean
    private OcrPersistenceService ocrPersistenceService;

    @Test
    void rejectsAnonymousSimulationReads() {
        client.get()
                .uri("/api/simulations")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsStudentAccessToSimulationTrash() {
        client.mutateWith(studentJwt())
                .get()
                .uri("/api/simulations/trash")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void scopesStudentSimulationReadsToAuthenticatedAccount() {
        when(simulationService.findAllActiveForAccount(42L)).thenReturn(Flux.empty());

        client.mutateWith(studentJwt())
                .get()
                .uri("/api/simulations")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");

        verify(simulationService).findAllActiveForAccount(42L);
    }

    @Test
    void scopesStudentAnswerReadsToAuthenticatedAccount() {
        when(simulationAnswerService.findAllActiveForAccount(42L)).thenReturn(Flux.empty());

        client.mutateWith(studentJwt())
                .get()
                .uri("/api/v1/simulation-answers")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");

        verify(simulationAnswerService).findAllActiveForAccount(42L);
    }

    @Test
    void rejectsStudentDeleteOfSimulation() {
        client.mutateWith(studentJwt())
                .delete()
                .uri("/api/simulations/1")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void rejectsAnonymousOcrExtraction() {
        client.post()
                .uri("/api/v1/ocr/extract/single")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsStudentOcrExtraction() {
        client.mutateWith(studentJwt())
                .post()
                .uri("/api/v1/ocr/extract/single")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void allowsTeacherOcrExtractionAndCallsClient() {
        when(ocrServiceClient.extractText(anyList())).thenReturn(Mono.just(successfulOcrResponse()));

        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", "exam-content".getBytes())
                .filename("exam.png")
                .contentType(MediaType.IMAGE_PNG);

        client.mutateWith(teacherJwt())
                .post()
                .uri("/api/v1/ocr/extract/single")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .exchange()
                .expectStatus().isOk();

        verify(ocrServiceClient).extractText(anyList());
    }

    @Test
    void passesAuthenticatedAccountToOcrPersistence() {
        Statement statement = new Statement("EXAM", "Mathematics exam");
        statement.setId(9L);
        when(ocrPersistenceService.processAndPersist(anyList(), eq(7L)))
                .thenReturn(Mono.just(new StatementWithRelations(
                        statement,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of()
                )));

        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("files", "exam-content".getBytes())
                .filename("exam.png")
                .contentType(MediaType.IMAGE_PNG);

        client.mutateWith(teacherJwt())
                .post()
                .uri("/api/v1/ocr/extract-and-persist")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .exchange()
                .expectStatus().isCreated();

        verify(ocrPersistenceService).processAndPersist(anyList(), eq(7L));
    }

    private static OcrResponse successfulOcrResponse() {
        return new OcrResponse(
                "success",
                "request-1",
                10,
                0.95,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null
        );
    }

    private static WebTestClientConfigurer studentJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "42",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))
        ));
    }

    private static WebTestClientConfigurer teacherJwt() {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                "7",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))
        ));
    }
}
