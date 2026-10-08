package ao.creativemode.kixi.chat.controller;

import ao.creativemode.kixi.chat.dto.ChatSendRequest;
import ao.creativemode.kixi.chat.dto.ChatSessionRequest;
import ao.creativemode.kixi.chat.dto.ChatSessionResponse;
import ao.creativemode.kixi.chat.dto.ChatStreamEvent;
import ao.creativemode.kixi.chat.service.ChatService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * AI tutor endpoints (issue #114), behind an explicit {@code /api/v1/chat/**}
 * rule in the security config.
 *
 * The message endpoint returns {@code Mono<ResponseEntity<Flux<…>>>} rather
 * than a bare {@code Flux}: every precondition (unknown session, exhausted
 * rate limit, missing API key) is resolved before the response entity exists,
 * so those failures reach {@code GlobalExceptionHandler} as 404/429/503 with
 * a problem-detail body. Once the entity exists and the SSE stream starts,
 * failures can only travel in-band as {@code error} events.
 */
@RestController
@RequestMapping("/api/v1/chat/sessions")
public class ChatController {

    private final ChatService chatService;
    private final CurrentAccountService currentAccountService;

    public ChatController(ChatService chatService,
                          CurrentAccountService currentAccountService) {
        this.chatService = chatService;
        this.currentAccountService = currentAccountService;
    }

    /** Open a session bound to a statement the caller may read. */
    @PostMapping
    public Mono<ResponseEntity<ChatSessionResponse>> createSession(
            @Valid @RequestBody ChatSessionRequest request,
            UriComponentsBuilder uriBuilder) {
        return currentAccountService.requiredAccountId()
                .flatMap(accountId ->
                        chatService.createSession(accountId, request.statementId()))
                .map(session -> {
                    URI location = uriBuilder
                            .path("/api/v1/chat/sessions/{id}")
                            .buildAndExpand(session.id())
                            .toUri();
                    return ResponseEntity.created(location).body(session);
                });
    }

    /**
     * Send one turn and stream the answer as server-sent events:
     * {@code delta} fragments, then {@code final}; on failure after the start,
     * one {@code error} event.
     */
    @PostMapping("/{id}/messages")
    public Mono<ResponseEntity<Flux<ServerSentEvent<ChatStreamEvent>>>> sendMessage(
            @PathVariable Long id,
            @Valid @RequestBody ChatSendRequest request) {
        return currentAccountService.requiredAccountId()
                .flatMap(accountId ->
                        chatService.streamMessage(accountId, id, request.content()))
                .map(stream -> ResponseEntity.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body(stream));
    }
}
