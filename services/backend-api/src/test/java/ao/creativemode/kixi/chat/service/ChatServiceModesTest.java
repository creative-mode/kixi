package ao.creativemode.kixi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.chat.client.GroqClient;
import ao.creativemode.kixi.chat.client.GroqMessage;
import ao.creativemode.kixi.chat.dto.ChatSessionRequest;
import ao.creativemode.kixi.chat.dto.ChatStreamEvent;
import ao.creativemode.kixi.chat.dto.TutorSource;
import ao.creativemode.kixi.chat.model.ChatMessage;
import ao.creativemode.kixi.chat.model.ChatRole;
import ao.creativemode.kixi.chat.model.ChatSession;
import ao.creativemode.kixi.chat.model.TutorMode;
import ao.creativemode.kixi.chat.repository.ChatMessageRepository;
import ao.creativemode.kixi.chat.repository.ChatSessionRepository;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.service.SimilarQuestionService;
import ao.creativemode.kixi.exams.service.SimilarQuestionService.Choice;
import ao.creativemode.kixi.exams.service.SimilarQuestionService.SimilarQuestion;
import ao.creativemode.kixi.exams.service.StatementService;
import ao.creativemode.kixi.exams.service.StatementWithQuestions;
import ao.creativemode.kixi.exams.service.TutorStyleService;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Issue #115: the tutor modes, with the AI client simulated (no key, no network in CI).
 * Statement 7 has question 41 (options 401 "A) 3" and 402 "B) 4") and question 42,
 * still awaiting review.
 */
class ChatServiceModesTest {

    private ChatSessionRepository sessionRepository;
    private ChatMessageRepository messageRepository;
    private StatementService statementService;
    private ChatRateLimiter rateLimiter;
    private GroqClient groqClient;
    private TutorStyleService tutorStyles;
    private SimilarQuestionService similarQuestions;
    private ChatService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChatSessionRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        statementService = mock(StatementService.class);
        rateLimiter = mock(ChatRateLimiter.class);
        groqClient = mock(GroqClient.class);
        tutorStyles = mock(TutorStyleService.class);
        similarQuestions = mock(SimilarQuestionService.class);
        when(tutorStyles.styleOf(any(Statement.class))).thenReturn(Mono.just(""));
        when(statementService.findByIdWithQuestionsVisible(7L)).thenReturn(Mono.just(material()));
        when(statementService.findByIdVisible(7L)).thenReturn(Mono.just(statement()));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> {
            ChatSession saved = invocation.getArgument(0);
            saved.setId(55L);
            return Mono.just(saved);
        });
        service = new ChatService(sessionRepository, messageRepository, statementService, rateLimiter,
                groqClient, tutorStyles, similarQuestions);
    }

    // === opening a session ================================================

    @Test
    void aModeAboutOneQuestionNeedsTheQuestion() {
        for (TutorMode mode : List.of(TutorMode.EXPLAIN_QUESTION, TutorMode.SIMILAR_EXERCISES)) {
            StepVerifier.create(service.createSession(42L, request(mode, null, null, null)))
                    .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatusCode()).isEqualTo(400))
                    .verify();
        }
        verify(sessionRepository, never()).save(any(ChatSession.class));
    }

    @Test
    void theQuestionMustBeALiveReviewedQuestionOfTheStatement() {
        // 99 is not in statement 7; 42 is, but still awaiting review (ADR-0010).
        for (Long questionId : List.of(99L, 42L)) {
            StepVerifier.create(service.createSession(42L, request(TutorMode.EXPLAIN_QUESTION, questionId, null, null)))
                    .expectErrorSatisfies(error -> assertThat(error.getMessage()).contains("does not belong"))
                    .verify();
        }
        verify(sessionRepository, never()).save(any(ChatSession.class));
    }

    @Test
    void theChosenOptionMustBeOfThatQuestion() {
        StepVerifier.create(service.createSession(42L, request(TutorMode.EXPLAIN_QUESTION, 41L, 999L, null)))
                .expectErrorSatisfies(error -> assertThat(error.getMessage())
                        .isEqualTo("Option does not belong to the question"))
                .verify();
        StepVerifier.create(service.createSession(42L, request(TutorMode.CONCEPT, null, 401L, null)))
                .expectErrorSatisfies(error -> assertThat(error.getMessage()).contains("needs a questionId"))
                .verify();
        verify(sessionRepository, never()).save(any(ChatSession.class));
    }

    @Test
    void anExplainSessionKeepsTheQuestionAndTheAnswerAndListsItsSources() {
        StepVerifier.create(service.createSession(42L, request(TutorMode.EXPLAIN_QUESTION, 41L, 401L, " achei que era 3 ")))
                .assertNext(response -> {
                    assertThat(response.mode()).isEqualTo(TutorMode.EXPLAIN_QUESTION);
                    assertThat(response.questionId()).isEqualTo(41L);
                    assertThat(response.sources()).extracting(TutorSource::kind).containsExactly("STATEMENT", "QUESTION");
                    assertThat(response.sources().get(1).questionNumber()).isEqualTo(1);
                })
                .verifyComplete();

        ArgumentCaptor<ChatSession> saved = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionRepository).save(saved.capture());
        assertThat(saved.getValue().getMode()).isEqualTo(TutorMode.EXPLAIN_QUESTION);
        assertThat(saved.getValue().getQuestionId()).isEqualTo(41L);
        assertThat(saved.getValue().getSelectedOptionId()).isEqualTo(401L);
        assertThat(saved.getValue().getStudentAnswer()).isEqualTo("achei que era 3");
    }

    @Test
    void withoutAModeTheSessionIsTheTutorOfBefore() {
        StepVerifier.create(service.createSession(42L, ChatSessionRequest.of(7L)))
                .assertNext(response -> {
                    assertThat(response.mode()).isEqualTo(TutorMode.GENERAL);
                    assertThat(response.questionId()).isNull();
                    assertThat(response.sources()).extracting(TutorSource::kind).containsExactly("STATEMENT");
                })
                .verifyComplete();
        verify(statementService, never()).findByIdWithQuestionsVisible(any());
    }

    // === answering ========================================================

    @Test
    void explainQuestionFocusesTheQuestionAndTheStudentsAnswerWithoutTheKey() {
        ChatSession session = session(TutorMode.EXPLAIN_QUESTION, 41L, 401L, "achei que era 3");

        List<ServerSentEvent<ChatStreamEvent>> events = answer(session);
        String prompt = systemPrompt();

        assertThat(prompt)
                .contains("MODO: EXPLICAR QUESTÃO")
                .contains("QUESTÃO EM FOCO\nQuestão 1: Quanto é 2+2?")
                .contains("Opção escolhida pelo aluno: A) 3")
                .contains("Resposta escrita pelo aluno: achei que era 3")
                .doesNotContain("isCorrect")
                .doesNotContain("correcta: B");
        // The mode is a rule: it comes before the material, right after the base rules.
        assertThat(prompt.indexOf("MODO:")).isLessThan(prompt.indexOf("ENUNCIADO"));
        assertThat(finalSources(events)).extracting(TutorSource::kind).containsExactly("STATEMENT", "QUESTION");
    }

    @Test
    void similarExercisesOfferRealCatalogQuestionsFirst() {
        when(similarQuestions.similarTo(any(Statement.class), any(Question.class), eq(ChatService.SIMILAR_LIMIT)))
                .thenReturn(Mono.just(List.of(new SimilarQuestion(77L, 3, "Quanto é 3+3?", "multiple_choice",
                        8L, "Exame de 2023", List.of(new Choice("A", "6"), new Choice("B", "9"))))));

        List<ServerSentEvent<ChatStreamEvent>> events = answer(session(TutorMode.SIMILAR_EXERCISES, 41L, null, null));
        String prompt = systemPrompt();

        assertThat(prompt)
                .contains("MODO: EXERCÍCIOS SEMELHANTES")
                .contains("Usa primeiro as questões semelhantes do catálogo")
                .contains("QUESTÕES SEMELHANTES DO CATÁLOGO\n- Enunciado \"Exame de 2023\", questão 3: Quanto é 3+3?")
                .contains("    A) 6");
        List<TutorSource> sources = finalSources(events);
        assertThat(sources).extracting(TutorSource::kind).containsExactly("STATEMENT", "QUESTION", "CATALOG_QUESTION");
        assertThat(sources.get(2).statementTitle()).isEqualTo("Exame de 2023");
        assertThat(sources.get(2).questionId()).isEqualTo(77L);
    }

    @Test
    void withNothingInTheCatalogTheTutorIsToldSo() {
        when(similarQuestions.similarTo(any(Statement.class), any(Question.class), anyInt()))
                .thenReturn(Mono.just(List.of()));

        answer(session(TutorMode.SIMILAR_EXERCISES, 41L, null, null));

        assertThat(systemPrompt()).contains("QUESTÕES SEMELHANTES DO CATÁLOGO\n(nenhuma encontrada)");
    }

    @Test
    void aBrokenCatalogLookupDoesNotTakeTheTutorDown() {
        when(similarQuestions.similarTo(any(Statement.class), any(Question.class), anyInt()))
                .thenReturn(Mono.error(new IllegalStateException("db down")));

        List<ServerSentEvent<ChatStreamEvent>> events = answer(session(TutorMode.SIMILAR_EXERCISES, 41L, null, null));

        assertThat(events.get(events.size() - 1).event()).isEqualTo("final");
        assertThat(systemPrompt()).contains("(nenhuma encontrada)");
    }

    @Test
    void conceptDoubtsExplainFromTheStatement() {
        List<ServerSentEvent<ChatStreamEvent>> events = answer(session(TutorMode.CONCEPT, null, null, null));

        assertThat(systemPrompt()).contains("MODO: DÚVIDAS DE CONCEITO").doesNotContain("QUESTÃO EM FOCO");
        assertThat(finalSources(events)).extracting(TutorSource::kind).containsExactly("STATEMENT");
        verify(similarQuestions, never()).similarTo(any(), any(), anyInt());
    }

    @Test
    void aGeneralSessionGetsThePromptOfBefore() {
        answer(session(TutorMode.GENERAL, null, null, null));

        assertThat(systemPrompt()).startsWith(TutorSystemPrompt.BASE + "\n\nENUNCIADO").doesNotContain("MODO:");
    }

    @Test
    void aQuestionGoneSinceTheSessionOpenedFallsBackToTheStatement() {
        // 42 went back to review after the session was opened.
        answer(session(TutorMode.EXPLAIN_QUESTION, 42L, null, null));

        assertThat(systemPrompt())
                .contains("a questão escolhida para esta sessão já não está disponível")
                .doesNotContain("QUESTÃO EM FOCO");
    }

    @Test
    void theModeComesBeforeTheTeachersStyleAndTheClosingNoteStaysLast() {
        String prompt = TutorSystemPrompt.compose("explica com exemplos",
                ChatService.instructionsFor(TutorMode.CONCEPT, null));

        assertThat(prompt.indexOf("MODO:")).isLessThan(prompt.indexOf("ESTILO DO PROFESSOR"));
        assertThat(prompt.strip()).endsWith("seguem as regras.");
        assertThat(TutorSystemPrompt.compose(null, "")).isEqualTo(TutorSystemPrompt.BASE);
    }

    // === fixtures =========================================================

    private List<ServerSentEvent<ChatStreamEvent>> answer(ChatSession session) {
        when(sessionRepository.findByIdAndAccountId(3L, 42L)).thenReturn(Mono.just(session));
        when(groqClient.isConfigured()).thenReturn(true);
        when(groqClient.getModel()).thenReturn("test-model");
        when(rateLimiter.check(42L)).thenReturn(Mono.empty());
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage saved = invocation.getArgument(0);
            saved.setId(saved.getRole() == ChatRole.USER ? 10L : 99L);
            return Mono.just(saved);
        });
        when(messageRepository.findBySessionIdOrderByCreatedAtDescIdDesc(3L)).thenReturn(Flux.empty());
        when(groqClient.streamChat(any())).thenReturn(Flux.just("ok"));
        return service.streamMessage(42L, 3L, "ajuda").flatMapMany(stream -> stream).collectList().block();
    }

    private String systemPrompt() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroqMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(groqClient).streamChat(captor.capture());
        return captor.getValue().get(0).content();
    }

    private static List<TutorSource> finalSources(List<ServerSentEvent<ChatStreamEvent>> events) {
        ServerSentEvent<ChatStreamEvent> last = events.get(events.size() - 1);
        assertThat(last.event()).isEqualTo("final");
        return last.data().sources();
    }

    private static ChatSessionRequest request(TutorMode mode, Long questionId, Long optionId, String answer) {
        return new ChatSessionRequest(7L, mode, questionId, optionId, answer);
    }

    private static ChatSession session(TutorMode mode, Long questionId, Long optionId, String answer) {
        ChatSession session = new ChatSession();
        session.setId(3L);
        session.setAccountId(42L);
        session.setStatementId(7L);
        session.setMode(mode);
        session.setQuestionId(questionId);
        session.setSelectedOptionId(optionId);
        session.setStudentAnswer(answer);
        return session;
    }

    private static Statement statement() {
        Statement statement = new Statement();
        statement.setId(7L);
        statement.setTitle("Exame Final de Matemática");
        statement.setSubjectId(5L);
        return statement;
    }

    private static StatementWithQuestions material() {
        Question question = new Question();
        question.setId(41L);
        question.setStatementId(7L);
        question.setNumber(1);
        question.setText("Quanto é 2+2?");
        question.setQuestionType("multiple_choice");
        question.setNeedsReview(false);

        Question unreviewed = new Question();
        unreviewed.setId(42L);
        unreviewed.setStatementId(7L);
        unreviewed.setNumber(2);
        unreviewed.setText("Ainda por rever");
        unreviewed.setNeedsReview(true);

        return new StatementWithQuestions(statement(), List.of(question, unreviewed),
                List.of(option(401L, 41L, "A", "3", 0, false), option(402L, 41L, "B", "4", 1, true)));
    }

    private static QuestionOption option(Long id, Long questionId, String label, String text, int order, boolean correct) {
        QuestionOption option = new QuestionOption();
        option.setId(id);
        option.setQuestionId(questionId);
        option.setOptionLabel(label);
        option.setOptionText(text);
        option.setOrderIndex(order);
        option.setIsCorrect(correct);
        return option;
    }
}
