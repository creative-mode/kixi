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

import ao.creativemode.kixi.training.dto.AutoTagResponse;
import ao.creativemode.kixi.training.dto.TopicRequest;
import ao.creativemode.kixi.training.dto.TopicResponse;
import ao.creativemode.kixi.training.service.TopicService;
import reactor.core.publisher.Flux;

/** Os temas por HTTP (kixi#729): quem lê, quem escreve, e o que chega ao serviço. */
@WebFluxTest(controllers = TopicController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class TopicControllerTest {

    private static final long TEACHER = 7L;
    private static final long STUDENT = 12L;
    private static final long ADMIN = 1L;

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private TopicService topics;

    private static TopicResponse topic() {
        return new TopicResponse(5L, 3L, null, "Funções", "função, gráfico", 4L);
    }

    @Test
    void anyAuthenticatedAccountListsTheTopicsOfASubject() {
        when(topics.list(3L, 10)).thenReturn(Flux.just(topic()));

        client.mutateWith(as(STUDENT, "STUDENT")).get().uri("/api/v1/subjects/3/topics?grade=10")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$[0].id").isEqualTo(5)
                .jsonPath("$[0].subjectId").isEqualTo(3)
                .jsonPath("$[0].name").isEqualTo("Funções")
                .jsonPath("$[0].keywords").isEqualTo("função, gráfico")
                .jsonPath("$[0].questionCount").isEqualTo(4);
    }

    @Test
    void listingNeedsASession() {
        client.get().uri("/api/v1/subjects/3/topics").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void aTeacherCreatesATopicAndGets201() {
        when(topics.create(eq(3L), any(TopicRequest.class), eq(TEACHER))).thenReturn(Mono.just(topic()));

        client.mutateWith(as(TEACHER, "TEACHER")).post().uri("/api/v1/subjects/3/topics")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Funções\",\"grade\":10,\"keywords\":\"função\"}")
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.id").isEqualTo(5);

        verify(topics).create(3L, new TopicRequest("Funções", 10, "função"), TEACHER);
    }

    @Test
    void aDuplicateTopicIs409() {
        when(topics.create(eq(3L), any(TopicRequest.class), anyLong()))
                .thenReturn(Mono.error(ApiException.conflict("Já existe um tema «Funções» nesta disciplina e classe.")));

        client.mutateWith(as(ADMIN, "ADMIN")).post().uri("/api/v1/subjects/3/topics")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"Funções\"}")
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void aStudentCannotWriteTopics() {
        client.mutateWith(as(STUDENT, "STUDENT")).post().uri("/api/v1/subjects/3/topics")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"Funções\"}")
                .exchange().expectStatus().isForbidden();
        client.mutateWith(as(STUDENT, "STUDENT")).put().uri("/api/v1/topics/5")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"X\"}")
                .exchange().expectStatus().isForbidden();
        client.mutateWith(as(STUDENT, "STUDENT")).delete().uri("/api/v1/topics/5").exchange().expectStatus().isForbidden();
        client.mutateWith(as(STUDENT, "STUDENT")).put().uri("/api/v1/questions/9/topics")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"topicIds\":[5]}")
                .exchange().expectStatus().isForbidden();
        client.mutateWith(as(STUDENT, "STUDENT")).post().uri("/api/v1/statements/4/topics/auto-tag")
                .exchange().expectStatus().isForbidden();

        verify(topics, never()).create(anyLong(), any(), anyLong());
        verify(topics, never()).delete(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void aBlankNameIs400() {
        client.mutateWith(as(TEACHER, "TEACHER")).post().uri("/api/v1/subjects/3/topics")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"  \"}")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void aTeacherUpdatesAndSoftDeletes() {
        when(topics.update(eq(5L), any(TopicRequest.class), eq(TEACHER), eq(false))).thenReturn(Mono.just(topic()));
        when(topics.delete(5L, TEACHER, false)).thenReturn(Mono.empty());

        client.mutateWith(as(TEACHER, "TEACHER")).put().uri("/api/v1/topics/5")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"Funções\"}")
                .exchange().expectStatus().isOk();
        client.mutateWith(as(TEACHER, "TEACHER")).delete().uri("/api/v1/topics/5").exchange().expectStatus().isNoContent();

        verify(topics).delete(5L, TEACHER, false);
    }

    @Test
    void onlyTheAuthorOrAnAdminChangesAndATopicInUseCannotBeDeleted() {
        when(topics.update(eq(5L), any(TopicRequest.class), eq(8L), eq(false)))
                .thenReturn(Mono.error(ApiException.forbidden("Só o autor do tema ou um administrador o pode alterar.")));
        when(topics.delete(5L, 8L, false))
                .thenReturn(Mono.error(ApiException.forbidden("Só o autor do tema ou um administrador o pode alterar.")));
        when(topics.delete(5L, TEACHER, false)).thenReturn(Mono.error(ApiException.conflict(
                "O tema está em uso numa matriz de prova; retire-o da matriz antes de o apagar.")));
        when(topics.delete(5L, ADMIN, true)).thenReturn(Mono.empty());

        client.mutateWith(as(8L, "TEACHER")).put().uri("/api/v1/topics/5")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"X\"}").exchange().expectStatus().isForbidden();
        client.mutateWith(as(8L, "TEACHER")).delete().uri("/api/v1/topics/5").exchange().expectStatus().isForbidden();
        client.mutateWith(as(TEACHER, "TEACHER")).delete().uri("/api/v1/topics/5").exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("em uso numa matriz"));
        client.mutateWith(as(ADMIN, "ADMIN")).delete().uri("/api/v1/topics/5").exchange().expectStatus().isNoContent();
    }

    @Test
    void aTeacherReplacesTheTopicsOfAQuestion() {
        when(topics.setQuestionTopics(eq(9L), any(), eq(TEACHER), eq(false))).thenReturn(Mono.just(List.of(topic())));

        client.mutateWith(as(TEACHER, "TEACHER")).put().uri("/api/v1/questions/9/topics")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"topicIds\":[5,6]}")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$[0].id").isEqualTo(5);

        verify(topics).setQuestionTopics(9L, List.of(5L, 6L), TEACHER, false);
    }

    @Test
    void autoTagReturnsTheCounts() {
        when(topics.autoTag(4L, ADMIN, true)).thenReturn(Mono.just(new AutoTagResponse(8, 2)));

        client.mutateWith(as(ADMIN, "ADMIN")).post().uri("/api/v1/statements/4/topics/auto-tag")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.tagged").isEqualTo(8).jsonPath("$.untagged").isEqualTo(2);
    }

    private static WebTestClientConfigurer as(long id, String role) {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(id), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
