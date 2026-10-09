package ao.creativemode.kixi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.chat.client.GroqClient;
import ao.creativemode.kixi.chat.client.GroqMessage;
import ao.creativemode.kixi.chat.exception.ChatRateLimitException;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

class ChatServiceTest {

    private ChatSessionRepository sessionRepository;
    private ChatMessageRepository messageRepository;
    private StatementService statementService;
    private ChatRateLimiter rateLimiter;
    private GroqClient groqClient;
    private ChatService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChatSessionRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        statementService = mock(StatementService.class);
        rateLimiter = mock(ChatRateLimiter.class);
        groqClient = mock(GroqClient.class);
        service = new ChatService(
                sessionRepository, messageRepository, statementService, rateLimiter, groqClient);
    }

    // === createSession ====================================================

    @Test
    void opensASessionOnAVisibleStatementForTheCaller() {
        when(statementService.findByIdVisible(7L)).thenReturn(Mono.just(statement(7L)));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> {
            ChatSession saved = invocation.getArgument(0);
            saved.setId(55L);
            return Mono.just(saved);
        });

        StepVerifier.create(service.createSession(42L, 7L))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(55L);
                    assertThat(response.statementId()).isEqualTo(7L);
                })
                .verifyComplete();

        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getAccountId()).isEqualTo(42L);
    }

    @Test
    void refusesToBindASessionToAnInvisibleStatement() {
        when(statementService.findByIdVisible(9L))
                .thenReturn(Mono.error(ApiException.notFound("Statement not found: 9")));

        StepVerifier.create(service.createSession(42L, 9L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(404);
                })
                .verify();

        verify(sessionRepository, never()).save(any(ChatSession.class));
    }

    // === preconditions before the stream ==================================

    @Test
    void treatsAnUnknownOrForeignSessionAsNotFound() {
        when(sessionRepository.findByIdAndAccountId(3L, 42L)).thenReturn(Mono.empty());

        StepVerifier.create(service.streamMessage(42L, 3L, "olá"))
                .expectErrorSatisfies(error ->
                        assertThat(((ApiException) error).getStatusCode()).isEqualTo(404))
                .verify();

        verify(rateLimiter, never()).check(anyLong());
        verify(messageRepository, never()).save(any(ChatMessage.class));
    }

    @Test
    void answers503WithoutSpendingTheWindowWhenNoKeyIsConfigured() {
        when(sessionRepository.findByIdAndAccountId(3L, 42L))
                .thenReturn(Mono.just(session(3L, 42L)));
        when(groqClient.isConfigured()).thenReturn(false);

        StepVerifier.create(service.streamMessage(42L, 3L, "olá"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(503);
                    assertThat(error.getMessage()).contains("APP_GROQ_API_KEY");
                })
                .verify();

        verify(rateLimiter, never()).check(anyLong());
        verify(messageRepository, never()).save(any(ChatMessage.class));
    }

    @Test
    void reportsTheExhaustedWindowAndStoresNothing() {
        when(sessionRepository.findByIdAndAccountId(3L, 42L))
                .thenReturn(Mono.just(session(3L, 42L)));
        when(groqClient.isConfigured()).thenReturn(true);
        when(rateLimiter.check(42L)).thenReturn(Mono.error(new ChatRateLimitException(42L)));

        StepVerifier.create(service.streamMessage(42L, 3L, "olá"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ChatRateLimitException.class);
                    assertThat(((ChatRateLimitException) error).getRetryAfterSeconds())
                            .isEqualTo(42L);
                })
                .verify();

        verify(messageRepository, never()).save(any(ChatMessage.class));
    }

    // === streaming ========================================================

    @Test
    void promptCarriesTheStatementAndQuestionsButNeverTheAnswerKey() {
        readySessionAndProvider(Flux.just("R"));

        StepVerifier.create(service.streamMessage(42L, 3L, "e a questão 4?").flatMapMany(f -> f))
                .expectNextCount(2) // delta + final
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroqMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(groqClient).streamChat(promptCaptor.capture());

        List<GroqMessage> prompt = promptCaptor.getValue();
        assertThat(prompt).hasSize(1);
        GroqMessage system = prompt.get(0);
        assertThat(system.role()).isEqualTo("system");
        assertThat(system.content())
                .contains("Kixi Tutor")
                .contains("ENUNCIADO")
                .contains("Título: Exame Final de Matemática")
                .contains("Questão 1 (10.0 pontos): Quanto é 2+2?")
                .contains("  A) 3")
                .contains("  B) 4")
                // the answer key never reaches the model — issue #106
                .doesNotContain("isCorrect");
    }

    @Test
    void keepsHistoryOldestFirstWithTheCurrentTurnIncluded() {
        readySessionAndProvider(Flux.just("ok"));
        ChatMessage previousAnswer = new ChatMessage(3L, ChatRole.ASSISTANT, "antes");
        previousAnswer.setId(1L);
        ChatMessage currentTurn = new ChatMessage(3L, ChatRole.USER, "agora");
        currentTurn.setId(2L);
        // newest first, as the repository returns them
        when(messageRepository.findBySessionIdOrderByCreatedAtDescIdDesc(3L))
                .thenReturn(Flux.just(currentTurn, previousAnswer));

        StepVerifier.create(service.streamMessage(42L, 3L, "agora").flatMapMany(f -> f))
                .expectNextCount(2)
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroqMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(groqClient).streamChat(promptCaptor.capture());
        List<GroqMessage> prompt = promptCaptor.getValue();
        assertThat(prompt).extracting(GroqMessage::role)
                .containsExactly("system", "assistant", "user");
        assertThat(prompt.get(1).content()).isEqualTo("antes");
        assertThat(prompt.get(2).content()).isEqualTo("agora");
    }

    @Test
    void persistsTheAnswerOnlyAfterTheStreamCompletes() {
        readySessionAndProvider(Flux.just("Bo", "a"));

        StepVerifier.create(service.streamMessage(42L, 3L, "pergunta").flatMapMany(f -> f))
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("delta");
                    assertThat(sse.data().content()).isEqualTo("Bo");
                })
                .assertNext(sse -> assertThat(sse.data().content()).isEqualTo("a"))
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("final");
                    assertThat(sse.data().messageId()).isEqualTo(99L);
                    assertThat(sse.data().model()).isEqualTo("test-model");
                })
                .verifyComplete();

        ArgumentCaptor<ChatMessage> saves = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository, times(2)).save(saves.capture());
        List<ChatMessage> stored = saves.getAllValues();
        assertThat(stored.get(0).getRole()).isEqualTo(ChatRole.USER);
        assertThat(stored.get(0).getContent()).isEqualTo("pergunta");
        assertThat(stored.get(1).getRole()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(stored.get(1).getContent()).isEqualTo("Boa");
        assertThat(stored.get(1).getModel()).isEqualTo("test-model");
    }

    @Test
    void reportsProviderFailuresInBandWithoutStoringAHalfAnswer() {
        readySessionAndProvider(Flux.error(ApiException.badGateway("provider exploded")));

        StepVerifier.create(service.streamMessage(42L, 3L, "pergunta").flatMapMany(f -> f))
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("error");
                    assertThat(sse.data().type()).isEqualTo("error");
                    assertThat(sse.data().message()).isEqualTo("provider exploded");
                })
                .verifyComplete();

        verify(messageRepository, times(1)).save(any(ChatMessage.class));
    }

    /**
     * Golden output of the context builder: what the model gets is exactly
     * this text — material only. The deleted option and the question still
     * awaiting review are absent, and nothing in the string can encode which
     * option is correct (isCorrect is never read).
     */
    @Test
    void contextCarriesTheMaterialAndNeverTheAnswerKey() {
        assertThat(ChatService.contextOf(withQuestions())).isEqualTo("""
                ENUNCIADO
                Título: Exame Final de Matemática
                Tipo: Avaliação Periódica
                Variante: A
                Instruções: Responda no caderno.

                Questão 1 (10.0 pontos): Quanto é 2+2?
                  A) 3
                  B) 4
                """);
    }

    // === fixtures =========================================================

    /** Session → rate limit → provider stubbed; empty history by default. */
    private void readySessionAndProvider(Flux<String> provider) {
        when(sessionRepository.findByIdAndAccountId(3L, 42L))
                .thenReturn(Mono.just(session(3L, 42L)));
        when(groqClient.isConfigured()).thenReturn(true);
        when(groqClient.getModel()).thenReturn("test-model");
        when(rateLimiter.check(42L)).thenReturn(Mono.empty());
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage saved = invocation.getArgument(0);
            saved.setId(saved.getRole() == ChatRole.USER ? 10L : 99L);
            return Mono.just(saved);
        });
        when(statementService.findByIdWithQuestionsVisible(7L))
                .thenReturn(Mono.just(withQuestions()));
        when(messageRepository.findBySessionIdOrderByCreatedAtDescIdDesc(3L))
                .thenReturn(Flux.empty());
        when(groqClient.streamChat(any())).thenReturn(provider);
    }

    private static ChatSession session(Long id, Long accountId) {
        ChatSession session = new ChatSession();
        session.setId(id);
        session.setAccountId(accountId);
        session.setStatementId(7L);
        return session;
    }

    private static Statement statement(Long id) {
        Statement statement = new Statement();
        statement.setId(id);
        statement.setTitle("Exame Final de Matemática");
        statement.setExamType("Avaliação Periódica");
        statement.setVariant("A");
        statement.setInstructions("Responda no caderno.");
        return statement;
    }

    private static StatementWithQuestions withQuestions() {
        Question question = new Question();
        question.setId(41L);
        question.setNumber(1);
        question.setMaxScore(10.0);
        question.setText("Quanto é 2+2?");
        question.setNeedsReview(false);

        Question unreviewed = new Question();
        unreviewed.setId(42L);
        unreviewed.setNumber(2);
        unreviewed.setText("Ainda por rever");
        unreviewed.setNeedsReview(true);

        QuestionOption deleted = option(41L, "C", "5", 2, false);
        deleted.setDeletedAt(LocalDateTime.now());

        return new StatementWithQuestions(
                statement(7L),
                List.of(question, unreviewed),
                // deliberately unordered: the context builder must sort them
                List.of(option(41L, "B", "4", 1, true), option(41L, "A", "3", 0, false),
                        deleted));
    }

    private static QuestionOption option(
            Long questionId, String label, String text, int order, boolean correct) {
        QuestionOption option = new QuestionOption();
        option.setQuestionId(questionId);
        option.setOptionLabel(label);
        option.setOptionText(text);
        option.setOrderIndex(order);
        option.setIsCorrect(correct);
        return option;
    }
}
