package ao.creativemode.kixi.chat.service;

import ao.creativemode.kixi.chat.client.GroqClient;
import ao.creativemode.kixi.chat.client.GroqMessage;
import ao.creativemode.kixi.chat.dto.ChatSessionResponse;
import ao.creativemode.kixi.chat.dto.ChatStreamEvent;
import ao.creativemode.kixi.chat.model.ChatMessage;
import ao.creativemode.kixi.chat.model.ChatRole;
import ao.creativemode.kixi.chat.model.ChatSession;
import ao.creativemode.kixi.chat.repository.ChatMessageRepository;
import ao.creativemode.kixi.chat.repository.ChatSessionRepository;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.service.StatementService;
import ao.creativemode.kixi.exams.service.StatementWithQuestions;
import ao.creativemode.kixi.shared.exception.ApiException;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The AI tutor of issue #114: a session bound to one statement, answers
 * streamed to the client, everything persisted.
 *
 * Three rules shape this class:
 * <ol>
 *   <li><b>Scoped material.</b> The prompt only ever contains the session's
 *       own statement (ADR-0010: validated domain data, never raw OCR), and
 *       the answer key is never read at all — the model cannot leak what it
 *       was never given (issue #106).</li>
 *   <li><b>Status codes before the stream.</b> Every precondition (unknown or
 *       foreign session, exhausted window, missing API key, vanished
 *       statement) fails inside the {@code Mono}, so the global handler turns
 *       it into 404/429/503 instead of an error event on a 200 response.
 *       Once the first byte is written only in-band {@code error} events can
 *       report a failure.</li>
 *   <li><b>Ownership by query.</b> Sessions are looked up by (id, account):
 *       someone else's session simply does not exist for the caller.</li>
 * </ol>
 */
@Service
public class ChatService {

    /** Recent turns sent back to the model; older ones fall out of the prompt. */
    static final int HISTORY_LIMIT = 20;

    static final String SYSTEM_PROMPT = """
            És o Kixi Tutor, o assistente de estudos da plataforma Kixi.
            Responde sempre em português, usando APENAS o enunciado e as questões
            fornecidos abaixo como material de trabalho.
            Se a pergunta não puder ser respondida com esse material, recusa
            educadamente e explica que só acompanhás o enunciado desta sessão —
            não inventes conteúdo nem respondas sobre outros assuntos.
            Não digas qual é a opção correcta: explica o raciocínio passo a passo
            e deixa a escolha ao aluno.
            """;

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final StatementService statementService;
    private final ChatRateLimiter rateLimiter;
    private final GroqClient groqClient;

    public ChatService(ChatSessionRepository sessionRepository,
                       ChatMessageRepository messageRepository,
                       StatementService statementService,
                       ChatRateLimiter rateLimiter,
                       GroqClient groqClient) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.statementService = statementService;
        this.rateLimiter = rateLimiter;
        this.groqClient = groqClient;
    }

    /**
     * Open a session on a statement the caller is allowed to read.
     * An invisible or deleted statement answers 404 before any row is written,
     * so no session can bind the tutor to material the account may not see.
     */
    public Mono<ChatSessionResponse> createSession(Long accountId, Long statementId) {
        return statementService.findByIdVisible(statementId)
                .flatMap(statement -> {
                    ChatSession session = new ChatSession();
                    session.setAccountId(accountId);
                    session.setStatementId(statement.getId());
                    return sessionRepository.save(session);
                })
                .map(session -> new ChatSessionResponse(
                        session.getId(), session.getStatementId(), session.getCreatedAt()));
    }

    /**
     * Answer one user turn. The returned {@code Mono} resolves only after
     * every precondition passed; the contained {@code Flux} is the SSE body.
     */
    public Mono<Flux<ServerSentEvent<ChatStreamEvent>>> streamMessage(
            Long accountId, Long sessionId, String content) {
        return sessionRepository.findByIdAndAccountId(sessionId, accountId)
                .switchIfEmpty(Mono.error(ApiException.notFound(
                        "Chat session not found: " + sessionId)))
                .flatMap(session -> {
                    if (!groqClient.isConfigured()) {
                        return Mono.error(ApiException.serviceUnavailable(
                                "AI tutor is not configured: set APP_GROQ_API_KEY."));
                    }
                    return rateLimiter.check(accountId)
                            .then(Mono.defer(() -> answer(session, content)));
                });
    }

    /**
     * Store the user's turn first, then load the statement and the history.
     * The history query runs after the insert, so the turn being answered is
     * part of what the model sees — no separate append that could drift.
     */
    private Mono<Flux<ServerSentEvent<ChatStreamEvent>>> answer(
            ChatSession session, String content) {
        return messageRepository.save(new ChatMessage(session.getId(), ChatRole.USER, content))
                .then(statementService.findByIdWithQuestionsVisible(session.getStatementId()))
                .flatMap(withQuestions -> historyOf(session)
                        .map(history -> stream(session, contextOf(withQuestions), history)));
    }

    private Mono<List<GroqMessage>> historyOf(ChatSession session) {
        return messageRepository.findBySessionIdOrderByCreatedAtDescIdDesc(session.getId())
                .take(HISTORY_LIMIT)
                .collectList()
                .map(found -> {
                    Collections.reverse(found); // newest first → oldest first
                    List<GroqMessage> history = new ArrayList<>(found.size());
                    found.forEach(message -> history.add(
                            new GroqMessage(roleOf(message.getRole()), message.getContent())));
                    return history;
                });
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> stream(
            ChatSession session, String context, List<GroqMessage> history) {
        List<GroqMessage> prompt = new ArrayList<>();
        prompt.add(new GroqMessage("system", SYSTEM_PROMPT + "\n\n" + context));
        prompt.addAll(history);

        StringBuilder answer = new StringBuilder();
        return groqClient.streamChat(prompt)
                .doOnNext(answer::append)
                .map(fragment -> ServerSentEvent
                        .<ChatStreamEvent>builder(ChatStreamEvent.delta(fragment))
                        .event("delta")
                        .build())
                .concatWith(persistAnswer(session, answer))
                // After the first event the status is committed: a provider
                // or persistence failure from here on can only be reported
                // in-band, as an error event on the same stream.
                .onErrorResume(error -> Flux.just(errorEvent(error)));
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> persistAnswer(
            ChatSession session, StringBuilder answer) {
        return Flux.defer(() -> {
            ChatMessage assistant =
                    new ChatMessage(session.getId(), ChatRole.ASSISTANT, answer.toString());
            assistant.setModel(groqClient.getModel());
            return messageRepository.save(assistant)
                    .map(saved -> ServerSentEvent
                            .<ChatStreamEvent>builder(
                                    ChatStreamEvent.finished(saved.getId(), saved.getModel()))
                            .event("final")
                            .build());
        });
    }

    private static ServerSentEvent<ChatStreamEvent> errorEvent(Throwable error) {
        String code = error instanceof ApiException api && api.getCode() != null
                ? api.getCode()
                : "CHAT_ANSWER_FAILED";
        String message = error instanceof ApiException
                ? error.getMessage()
                : "The tutor could not answer. Try again.";
        return ServerSentEvent.builder(ChatStreamEvent.error(code, message))
                .event("error")
                .build();
    }

    private static String roleOf(ChatRole role) {
        return role == ChatRole.ASSISTANT ? "assistant" : "user";
    }

    /**
     * The tutor's whole world: statement metadata plus its questions and
     * options, as plain text.
     *
     * {@code isCorrect} is never read — the answer key must not reach the
     * model, and through it the student, before submission (issue #106).
     * Questions still awaiting review are skipped: ADR-0010 restricts the AI
     * layer to validated data.
     */
    static String contextOf(StatementWithQuestions withQuestions) {
        Statement statement = withQuestions.statement();
        StringBuilder context = new StringBuilder("ENUNCIADO\n");
        appendLine(context, "Título", statement.getTitle());
        appendLine(context, "Tipo", statement.getExamType());
        appendLine(context, "Variante", statement.getVariant());
        appendLine(context, "Instruções", statement.getInstructions());

        Map<Long, List<QuestionOption>> optionsByQuestion = withQuestions.options().stream()
                .filter(option -> option.getDeletedAt() == null)
                .collect(Collectors.groupingBy(
                        QuestionOption::getQuestionId, LinkedHashMap::new, Collectors.toList()));

        int position = 0;
        for (Question question : withQuestions.questions()) {
            if (question.getDeletedAt() != null
                    || Boolean.TRUE.equals(question.getNeedsReview())) {
                continue;
            }
            position++;
            context.append('\n');
            context.append("Questão ").append(question.getNumber() != null
                    ? question.getNumber() : position);
            if (question.getMaxScore() != null) {
                context.append(" (").append(question.getMaxScore()).append(" pontos)");
            }
            context.append(": ").append(orEmpty(question.getText())).append('\n');

            optionsByQuestion
                    .getOrDefault(question.getId(), List.of())
                    .stream()
                    .sorted(Comparator
                            .comparing(QuestionOption::getOrderIndex,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(option -> orEmpty(option.getOptionLabel())))
                    .forEach(option -> context
                            .append("  ")
                            .append(option.getOptionLabel() != null
                                    ? option.getOptionLabel() + ") "
                                    : "- ")
                            .append(orEmpty(option.getOptionText()))
                            .append('\n'));
        }
        return context.toString();
    }

    private static void appendLine(StringBuilder context, String label, String value) {
        if (value != null && !value.isBlank()) {
            context.append(label).append(": ").append(value).append('\n');
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
