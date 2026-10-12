package ao.creativemode.kixi.chat.service;

import ao.creativemode.kixi.chat.client.GroqClient;
import ao.creativemode.kixi.chat.client.GroqMessage;
import ao.creativemode.kixi.chat.dto.ChatSessionRequest;
import ao.creativemode.kixi.chat.dto.ChatSessionResponse;
import ao.creativemode.kixi.chat.dto.TutorSource;
import ao.creativemode.kixi.chat.model.TutorMode;
import ao.creativemode.kixi.chat.dto.ChatStreamEvent;
import ao.creativemode.kixi.chat.model.ChatMessage;
import ao.creativemode.kixi.chat.model.ChatRole;
import ao.creativemode.kixi.chat.model.ChatSession;
import ao.creativemode.kixi.chat.repository.ChatMessageRepository;
import ao.creativemode.kixi.chat.repository.ChatSessionRepository;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.service.SimilarQuestionService;
import ao.creativemode.kixi.exams.service.SimilarQuestionService.SimilarQuestion;
import ao.creativemode.kixi.exams.service.StatementService;
import ao.creativemode.kixi.exams.service.TutorStyleService;
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

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final StatementService statementService;
    private final ChatRateLimiter rateLimiter;
    private final GroqClient groqClient;
    private final TutorStyleService tutorStyles;
    private final SimilarQuestionService similarQuestions;

    /** Real catalog questions offered per answer in "exercícios semelhantes" (#115). */
    static final int SIMILAR_LIMIT = 3;

    public ChatService(ChatSessionRepository sessionRepository,
                       ChatMessageRepository messageRepository,
                       StatementService statementService,
                       ChatRateLimiter rateLimiter,
                       GroqClient groqClient,
                       TutorStyleService tutorStyles,
                       SimilarQuestionService similarQuestions) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.statementService = statementService;
        this.rateLimiter = rateLimiter;
        this.groqClient = groqClient;
        this.tutorStyles = tutorStyles;
        this.similarQuestions = similarQuestions;
    }

    /**
     * Open a session on a statement the caller is allowed to read.
     * An invisible or deleted statement answers 404 before any row is written,
     * so no session can bind the tutor to material the account may not see.
     */
    public Mono<ChatSessionResponse> createSession(Long accountId, Long statementId) {
        return createSession(accountId, ChatSessionRequest.of(statementId));
    }

    /**
     * Issue #115: a session may also say what it is for. Everything is checked
     * before a row is written: a mode that needs a question gets one, the question
     * belongs to the statement and is reviewed (ADR-0010), and the student's chosen
     * option belongs to that question. Failures are 400s, not sessions that would
     * point the tutor at material it may not use.
     */
    public Mono<ChatSessionResponse> createSession(Long accountId, ChatSessionRequest request) {
        TutorMode mode = request.mode() == null ? TutorMode.GENERAL : request.mode();
        boolean answered = request.selectedOptionId() != null || hasText(request.studentAnswer());
        if (mode.needsQuestion() && request.questionId() == null) {
            return Mono.error(ApiException.badRequest("questionId is required for mode " + mode));
        }
        if (request.questionId() == null) {
            if (answered) {
                return Mono.error(ApiException.badRequest("The student's answer needs a questionId"));
            }
            return statementService.findByIdVisible(request.statementId())
                    .flatMap(statement -> save(accountId, statement, mode, null, request));
        }
        return statementService.findByIdWithQuestionsVisible(request.statementId())
                .flatMap(withQuestions -> {
                    Question focus = focusOf(withQuestions, request.questionId());
                    if (focus == null) {
                        return Mono.error(ApiException.badRequest(
                                "Question does not belong to the statement: " + request.questionId()));
                    }
                    if (request.selectedOptionId() != null
                            && optionOf(withQuestions, focus, request.selectedOptionId()) == null) {
                        return Mono.error(ApiException.badRequest("Option does not belong to the question"));
                    }
                    return save(accountId, withQuestions.statement(), mode, focus, request);
                });
    }

    private Mono<ChatSessionResponse> save(Long accountId, Statement statement, TutorMode mode,
            Question focus, ChatSessionRequest request) {
        ChatSession session = new ChatSession();
        session.setAccountId(accountId);
        session.setStatementId(statement.getId());
        session.setMode(mode);
        if (focus != null) {
            session.setQuestionId(focus.getId());
            session.setSelectedOptionId(request.selectedOptionId());
            session.setStudentAnswer(hasText(request.studentAnswer()) ? request.studentAnswer().strip() : null);
        }
        return sessionRepository.save(session)
                .map(saved -> new ChatSessionResponse(
                        saved.getId(), saved.getStatementId(), saved.getCreatedAt(),
                        saved.getMode(), saved.getQuestionId(),
                        sourcesOf(statement, focus, List.of())));
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
                .flatMap(withQuestions -> {
                    TutorMode mode = session.getMode();
                    // The question may have been deleted or sent back to review since the
                    // session opened: then there is simply no question in focus.
                    Question focus = focusOf(withQuestions, session.getQuestionId());
                    Mono<String> style = tutorStyles.styleOf(withQuestions.statement())
                            .defaultIfEmpty("")
                            // A style that cannot be read must not take the tutor down with it:
                            // the prompt without a voice is still a working tutor.
                            .onErrorResume(error -> Mono.just(""));
                    Mono<List<SimilarQuestion>> similar = mode == TutorMode.SIMILAR_EXERCISES && focus != null
                            ? similarQuestions.similarTo(withQuestions.statement(), focus, SIMILAR_LIMIT)
                                    .defaultIfEmpty(List.of())
                                    // No catalog is not a reason to fail: the tutor then makes
                                    // new exercises, and says so.
                                    .onErrorResume(error -> Mono.just(List.of()))
                            : Mono.just(List.of());
                    return Mono.zip(style, similar).flatMap(found -> historyOf(session)
                            .map(history -> stream(
                                    session,
                                    TutorSystemPrompt.compose(found.getT1(), instructionsFor(mode, focus)),
                                    contextOf(withQuestions)
                                            + focusContext(withQuestions, focus, session)
                                            + similarContext(mode, focus, found.getT2()),
                                    history,
                                    sourcesOf(withQuestions.statement(), focus, found.getT2()))));
                });
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
            ChatSession session, String systemPrompt, String context, List<GroqMessage> history,
            List<TutorSource> sources) {
        List<GroqMessage> prompt = new ArrayList<>();
        prompt.add(new GroqMessage("system", systemPrompt + "\n\n" + context));
        prompt.addAll(history);

        StringBuilder answer = new StringBuilder();
        return groqClient.streamChat(prompt)
                .doOnNext(answer::append)
                .map(fragment -> ServerSentEvent
                        .<ChatStreamEvent>builder(ChatStreamEvent.delta(fragment))
                        .event("delta")
                        .build())
                .concatWith(persistAnswer(session, answer, sources))
                // After the first event the status is committed: a provider
                // or persistence failure from here on can only be reported
                // in-band, as an error event on the same stream.
                .onErrorResume(error -> Flux.just(errorEvent(error)));
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> persistAnswer(
            ChatSession session, StringBuilder answer, List<TutorSource> sources) {
        return Flux.defer(() -> {
            ChatMessage assistant =
                    new ChatMessage(session.getId(), ChatRole.ASSISTANT, answer.toString());
            assistant.setModel(groqClient.getModel());
            return messageRepository.save(assistant)
                    .map(saved -> ServerSentEvent
                            .<ChatStreamEvent>builder(
                                    ChatStreamEvent.finished(saved.getId(), saved.getModel(), sources))
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

    // === tutor modes (issue #115) =========================================

    /**
     * The rules each mode adds. They sit with the base rules, before the teacher's
     * style (see {@link TutorSystemPrompt#compose(String, String)}). GENERAL adds
     * nothing, so a session of #114 gets the same prompt as before.
     */
    static String instructionsFor(TutorMode mode, Question focus) {
        if (mode.needsQuestion() && focus == null) {
            return """
                    MODO: a questão escolhida para esta sessão já não está disponível.
                    Ajuda o aluno com o enunciado em geral.
                    """;
        }
        return switch (mode) {
            case GENERAL -> "";
            case EXPLAIN_QUESTION -> """
                    MODO: EXPLICAR QUESTÃO. Concentra-te na questão em foco indicada no
                    material. Explica o que ela pede e o raciocínio passo a passo. Se o
                    material trouxer a resposta do aluno, mostra onde esse raciocínio pode
                    ter falhado, sem dizer qual é a opção correcta.
                    """;
            case SIMILAR_EXERCISES -> """
                    MODO: EXERCÍCIOS SEMELHANTES. Propõe exercícios parecidos com a questão
                    em foco. Usa primeiro as questões semelhantes do catálogo listadas no
                    material e diz de que enunciado vem cada uma. Só quando não houver
                    nenhuma, ou o aluno pedir mais, podes criar exercícios novos sobre o
                    mesmo tema, e tens de dizer que foram criados por ti. Não dês as
                    soluções logo: deixa o aluno tentar primeiro.
                    """;
            case CONCEPT -> """
                    MODO: DÚVIDAS DE CONCEITO. O aluno quer perceber um conceito. Explica-o
                    com palavras simples e exemplos curtos, partindo do que aparece no
                    enunciado (e na questão em foco, se houver). No fim, confirma com uma
                    pergunta curta se ficou claro.
                    """;
        };
    }

    /** The question in focus and, when given, the student's answer to it. */
    static String focusContext(StatementWithQuestions withQuestions, Question focus, ChatSession session) {
        if (focus == null) return "";
        StringBuilder context = new StringBuilder("\nQUESTÃO EM FOCO\n");
        context.append("Questão ").append(focus.getNumber() != null ? focus.getNumber() : "?")
                .append(": ").append(orEmpty(focus.getText())).append('\n');
        QuestionOption chosen = optionOf(withQuestions, focus, session.getSelectedOptionId());
        if (chosen != null) {
            context.append("Opção escolhida pelo aluno: ")
                    .append(chosen.getOptionLabel() != null ? chosen.getOptionLabel() + ") " : "")
                    .append(orEmpty(chosen.getOptionText())).append('\n');
        }
        if (hasText(session.getStudentAnswer())) {
            // The student's own words, flattened: they are data about the attempt.
            context.append("Resposta escrita pelo aluno: ")
                    .append(session.getStudentAnswer().replaceAll("\\s+", " ").strip()).append('\n');
        }
        return context.toString();
    }

    /** The catalog questions offered as similar exercises, without the answer key. */
    static String similarContext(TutorMode mode, Question focus, List<SimilarQuestion> similar) {
        if (mode != TutorMode.SIMILAR_EXERCISES || focus == null) return "";
        StringBuilder context = new StringBuilder("\nQUESTÕES SEMELHANTES DO CATÁLOGO\n");
        if (similar.isEmpty()) {
            return context.append("(nenhuma encontrada)\n").toString();
        }
        for (SimilarQuestion question : similar) {
            context.append("- Enunciado \"").append(orEmpty(question.statementTitle())).append("\", questão ")
                    .append(question.number() != null ? question.number() : "?").append(": ")
                    .append(orEmpty(question.text())).append('\n');
            for (SimilarQuestionService.Choice choice : question.choices()) {
                context.append("    ").append(choice.label() != null ? choice.label() + ") " : "- ")
                        .append(orEmpty(choice.text())).append('\n');
            }
        }
        return context.toString();
    }

    /** What the answer was built from, for "fonte da resposta". */
    static List<TutorSource> sourcesOf(Statement statement, Question focus, List<SimilarQuestion> similar) {
        List<TutorSource> sources = new ArrayList<>();
        sources.add(TutorSource.statement(statement.getId(), statement.getTitle()));
        if (focus != null) {
            sources.add(TutorSource.question(statement.getId(), statement.getTitle(), focus.getId(), focus.getNumber()));
        }
        for (SimilarQuestion question : similar) {
            sources.add(TutorSource.catalogQuestion(question.statementId(), question.statementTitle(),
                    question.questionId(), question.number()));
        }
        return List.copyOf(sources);
    }

    /** A live, reviewed question of the statement, or null. */
    static Question focusOf(StatementWithQuestions withQuestions, Long questionId) {
        if (questionId == null) return null;
        return withQuestions.questions().stream()
                .filter(question -> questionId.equals(question.getId()))
                .filter(question -> question.getDeletedAt() == null
                        && !Boolean.TRUE.equals(question.getNeedsReview()))
                .findFirst()
                .orElse(null);
    }

    private static QuestionOption optionOf(StatementWithQuestions withQuestions, Question question, Long optionId) {
        if (optionId == null) return null;
        return withQuestions.options().stream()
                .filter(option -> optionId.equals(option.getId())
                        && question.getId().equals(option.getQuestionId())
                        && option.getDeletedAt() == null)
                .findFirst()
                .orElse(null);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
