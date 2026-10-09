package ao.creativemode.kixi.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import ao.creativemode.kixi.chat.dto.ChatSessionResponse;
import ao.creativemode.kixi.chat.dto.ChatStreamEvent;
import ao.creativemode.kixi.chat.exception.ChatRateLimitException;
import ao.creativemode.kixi.chat.service.ChatService;
import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClientConfigurer;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The tutor's security chain and status codes end to end (issue #114).
 *
 * The rule under test is the explicit {@code /api/v1/chat/**} matcher in
 * {@link SecurityConfig}: anonymous requests must never reach the controller,
 * while any signed-in role may — and the controller's error statuses (404 for
 * a foreign session, 429 with Retry-After, 503 without a key) must survive
 * the {@code Mono<ResponseEntity<Flux<…>>>} shape of the SSE endpoint.
 */
@WebFluxTest(controllers = {ChatController.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class ChatAuthorizationTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private ChatService chatService;

    @Test
    void rejectsAnonymousSessionCreation() {
        client.post()
                .uri("/api/v1/chat/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("statementId", 7L))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsAnonymousMessages() {
        client.post()
                .uri("/api/v1/chat/sessions/5/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "olá"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void studentOpensASessionOnItsOwnStatement() {
        when(chatService.createSession(42L, 7L))
                .thenReturn(Mono.just(new ChatSessionResponse(3L, 7L, LocalDateTime.now())));

        client.mutateWith(studentJwt())
                .post()
                .uri("/api/v1/chat/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("statementId", 7L))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().value("Location",
                        location -> assertThat(location).endsWith("/api/v1/chat/sessions/3"));

        verify(chatService).createSession(42L, 7L);
    }

    @Test
    void teacherMayOpenASessionToo() {
        when(chatService.createSession(7L, 9L))
                .thenReturn(Mono.just(new ChatSessionResponse(4L, 9L, LocalDateTime.now())));

        client.mutateWith(teacherJwt())
                .post()
                .uri("/api/v1/chat/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("statementId", 9L))
                .exchange()
                .expectStatus().isCreated();

        verify(chatService).createSession(7L, 9L);
    }

    // === statuses of the message endpoint =================================

    @Test
    void streamsTheAnswerAsServerSentEvents() {
        when(chatService.streamMessage(42L, 5L, "olá")).thenReturn(Mono.just(
                Flux.just(
                        ServerSentEvent.<ChatStreamEvent>builder(
                                ChatStreamEvent.delta("Ol")).event("delta").build(),
                        ServerSentEvent.<ChatStreamEvent>builder(
                                ChatStreamEvent.finished(11L, "test-model")).event("final").build())));

        client.mutateWith(studentJwt())
                .post()
                .uri("/api/v1/chat/sessions/5/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "olá"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("event:delta")
                        .contains("\"type\":\"delta\"")
                        .contains("event:final"));

        verify(chatService).streamMessage(42L, 5L, "olá");
    }

    @Test
    void unknownOrForeignSessionAnswers404() {
        when(chatService.streamMessage(42L, 5L, "olá")).thenReturn(
                Mono.error(ApiException.notFound("Chat session not found: 5")));

        client.mutateWith(studentJwt())
                .post()
                .uri("/api/v1/chat/sessions/5/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "olá"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void exhaustedWindowAnswers429WithRetryAfter() {
        when(chatService.streamMessage(42L, 5L, "olá")).thenReturn(
                Mono.error(new ChatRateLimitException(42L)));

        client.mutateWith(studentJwt())
                .post()
                .uri("/api/v1/chat/sessions/5/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "olá"))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "42");
    }

    @Test
    void answers503WithAClearMessageWhenNoKeyIsConfigured() {
        when(chatService.streamMessage(42L, 5L, "olá")).thenReturn(
                Mono.error(ApiException.serviceUnavailable(
                        "AI tutor is not configured: set APP_GROQ_API_KEY.")));

        client.mutateWith(studentJwt())
                .post()
                .uri("/api/v1/chat/sessions/5/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "olá"))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("APP_GROQ_API_KEY"));
    }

    @Test
    void blankMessageNeverReachesTheService() {
        client.mutateWith(studentJwt())
                .post()
                .uri("/api/v1/chat/sessions/5/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "   "))
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(chatService);
    }

    // === helpers ==========================================================

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
