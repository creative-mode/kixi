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

import ao.creativemode.kixi.training.dto.MatrixRequest;
import ao.creativemode.kixi.training.dto.MatrixResponse;
import ao.creativemode.kixi.training.service.MatrixService;

/** A matriz da prova por HTTP (kixi#729). */
@WebFluxTest(controllers = ExamMatrixController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class ExamMatrixControllerTest {

    private static final long TEACHER = 7L;
    private static final long STUDENT = 12L;
    private static final long ROOM = 40L;

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private MatrixService matrices;

    private static MatrixResponse matrix() {
        return new MatrixResponse(ROOM, "Matriz", 20.0, List.of(
                new MatrixResponse.Item(1L, 5L, "Funções", 3, 8.0, "KNOWLEDGE", List.of("múltipla escolha"))));
    }

    @Test
    void theTeacherPutsAMatrixAndTheServiceSeesTheBody() {
        when(matrices.put(eq(ROOM), any(MatrixRequest.class), eq(TEACHER), eq(false))).thenReturn(Mono.just(matrix()));

        client.mutateWith(as(TEACHER, "TEACHER")).put().uri("/api/v1/exam-rooms/" + ROOM + "/matrix")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"title\":\"Matriz\",\"totalPoints\":20,\"items\":[{\"topicId\":5,\"questionCount\":3,\"points\":8,"
                        + "\"cognitiveLevel\":\"KNOWLEDGE\",\"questionTypes\":[\"múltipla escolha\"]}]}")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.roomId").isEqualTo(ROOM)
                .jsonPath("$.totalPoints").isEqualTo(20.0)
                .jsonPath("$.items[0].topicName").isEqualTo("Funções")
                .jsonPath("$.items[0].cognitiveLevel").isEqualTo("KNOWLEDGE")
                .jsonPath("$.items[0].questionTypes[0]").isEqualTo("múltipla escolha");

        verify(matrices).put(eq(ROOM), any(MatrixRequest.class), eq(TEACHER), eq(false));
    }

    @Test
    void aBadSumIs400WithTheMessage() {
        when(matrices.put(eq(ROOM), any(), anyLong(), anyBoolean())).thenReturn(Mono.error(ApiException.badRequest(
                "A soma das cotações (16) tem de ser igual ao total da prova (20).")));

        client.mutateWith(as(TEACHER, "TEACHER")).put().uri("/api/v1/exam-rooms/" + ROOM + "/matrix")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"items\":[{\"topicId\":5,\"questionCount\":3,\"points\":16}]}")
                .exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("A soma das cotações"));
    }

    @Test
    void anEmptyItemsListIsRefusedBeforeTheService() {
        client.mutateWith(as(TEACHER, "TEACHER")).put().uri("/api/v1/exam-rooms/" + ROOM + "/matrix")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"items\":[]}")
                .exchange().expectStatus().isBadRequest();
        verify(matrices, never()).put(anyLong(), any(), anyLong(), anyBoolean());
    }

    @Test
    void importAcceptsCsvAndPlainText() {
        when(matrices.importCsv(eq(ROOM), any(), any(), any(), eq(TEACHER), eq(false))).thenReturn(Mono.just(matrix()));

        client.mutateWith(as(TEACHER, "TEACHER")).post().uri("/api/v1/exam-rooms/" + ROOM + "/matrix/import")
                .contentType(MediaType.parseMediaType("text/csv")).bodyValue("Tema;Questões\nFunções;3\n")
                .exchange().expectStatus().isOk();
        client.mutateWith(as(TEACHER, "TEACHER")).post().uri("/api/v1/exam-rooms/" + ROOM + "/matrix/import?totalPoints=10&title=T")
                .contentType(MediaType.TEXT_PLAIN).bodyValue("Funções;3")
                .exchange().expectStatus().isOk();

        verify(matrices).importCsv(ROOM, "Tema;Questões\nFunções;3\n", null, null, TEACHER, false);
        verify(matrices).importCsv(ROOM, "Funções;3", "T", 10.0, TEACHER, false);
    }

    @Test
    void anImportWithTheWrongContentTypeIs415() {
        client.mutateWith(as(TEACHER, "TEACHER")).post().uri("/api/v1/exam-rooms/" + ROOM + "/matrix/import")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isEqualTo(415);
    }

    @Test
    void aStudentReadsButNeverWrites() {
        when(matrices.get(ROOM, STUDENT, false)).thenReturn(Mono.just(matrix()));

        client.mutateWith(as(STUDENT, "STUDENT")).get().uri("/api/v1/exam-rooms/" + ROOM + "/matrix")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.items[0].topicId").isEqualTo(5);
        client.mutateWith(as(STUDENT, "STUDENT")).put().uri("/api/v1/exam-rooms/" + ROOM + "/matrix")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"items\":[{\"topicId\":5,\"questionCount\":1}]}")
                .exchange().expectStatus().isForbidden();
        client.mutateWith(as(STUDENT, "STUDENT")).delete().uri("/api/v1/exam-rooms/" + ROOM + "/matrix")
                .exchange().expectStatus().isForbidden();
        client.mutateWith(as(STUDENT, "STUDENT")).post().uri("/api/v1/exam-rooms/" + ROOM + "/matrix/import")
                .contentType(MediaType.TEXT_PLAIN).bodyValue("Funções;3")
                .exchange().expectStatus().isForbidden();

        verify(matrices, never()).delete(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void noMatrixIs404() {
        when(matrices.get(ROOM, STUDENT, false)).thenReturn(Mono.error(ApiException.notFound("Esta prova ainda não tem matriz.")));
        client.mutateWith(as(STUDENT, "STUDENT")).get().uri("/api/v1/exam-rooms/" + ROOM + "/matrix")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void anotherTeachersRoomIs403AndDeleteIs204() {
        when(matrices.delete(ROOM, 8L, false)).thenReturn(Mono.error(ApiException.forbidden("Só o professor dono da sala pode alterar a sua matriz.")));
        when(matrices.delete(ROOM, TEACHER, false)).thenReturn(Mono.empty());

        client.mutateWith(as(8L, "TEACHER")).delete().uri("/api/v1/exam-rooms/" + ROOM + "/matrix").exchange().expectStatus().isForbidden();
        client.mutateWith(as(TEACHER, "TEACHER")).delete().uri("/api/v1/exam-rooms/" + ROOM + "/matrix").exchange().expectStatus().isNoContent();
    }

    @Test
    void anAdminIsFlaggedAsAdmin() {
        when(matrices.get(ROOM, 1L, true)).thenReturn(Mono.just(matrix()));
        client.mutateWith(as(1L, "ADMIN")).get().uri("/api/v1/exam-rooms/" + ROOM + "/matrix").exchange().expectStatus().isOk();
        verify(matrices).get(ROOM, 1L, true);
    }

    private static WebTestClientConfigurer as(long id, String role) {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(id), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
