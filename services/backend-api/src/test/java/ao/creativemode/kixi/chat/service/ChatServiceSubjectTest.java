package ao.creativemode.kixi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.chat.client.GroqClient;
import ao.creativemode.kixi.chat.client.GroqMessage;
import ao.creativemode.kixi.chat.dto.ChatSessionRequest;
import ao.creativemode.kixi.chat.dto.TutorSource;
import ao.creativemode.kixi.chat.model.ChatMessage;
import ao.creativemode.kixi.chat.model.ChatRole;
import ao.creativemode.kixi.chat.model.ChatSession;
import ao.creativemode.kixi.chat.model.TutorMode;
import ao.creativemode.kixi.chat.repository.ChatMessageRepository;
import ao.creativemode.kixi.chat.repository.ChatSessionRepository;
import ao.creativemode.kixi.exams.service.SimilarQuestionService;
import ao.creativemode.kixi.exams.service.StatementService;
import ao.creativemode.kixi.exams.service.TutorStyleService;
import ao.creativemode.kixi.shared.exception.ApiException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

/**
 * Issue #610: the tutor outside one paper. A session is opened by subject or by
 * one topic of the exam matrix, reads the catalog of the papers the student may
 * see, and stays closed while a simulation of that subject is live.
 */
class ChatServiceSubjectTest {

    private static final Long ACCOUNT = 42L;
    private static final Long SUBJECT = 5L;
    private static final Long TOPIC = 8L;

    private ChatSessionRepository sessionRepository;
    private ChatMessageRepository messageRepository;
    private ChatRateLimiter rateLimiter;
    private GroqClient groqClient;
    private TutorSubjectCatalog subjectCatalog;
    private TutorExamGuard examGuard;
    private ChatService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChatSessionRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        rateLimiter = mock(ChatRateLimiter.class);
        groqClient = mock(GroqClient.class);
        subjectCatalog = mock(TutorSubjectCatalog.class);
        examGuard = mock(TutorExamGuard.class);
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> {
            ChatSession saved = invocation.getArgument(0);
            saved.setId(55L);
            return Mono.just(saved);
        });
        when(examGuard.requireNoLiveExamInSubject(ACCOUNT, SUBJECT)).thenReturn(Mono.empty());
        service = new ChatService(sessionRepository, messageRepository, mock(StatementService.class),
                rateLimiter, groqClient, mock(TutorStyleService.class),
                mock(SimilarQuestionService.class), subjectCatalog, examGuard);
    }

    // === opening a session ================================================

    @Test
    void opensASessionOnASubjectAndKeepsTheSubjectInsteadOfAStatement() {
        when(subjectCatalog.read(SUBJECT, null)).thenReturn(Mono.just(material(null)));

        StepVerifier.create(service.createSession(ACCOUNT, ChatSessionRequest.ofSubject(SUBJECT, null)))
                .assertNext(response -> {
                    assertThat(response.statementId()).isNull();
                    assertThat(response.subjectId()).isEqualTo(SUBJECT);
                    assertThat(response.topicId()).isNull();
                    assertThat(response.mode()).isEqualTo(TutorMode.GENERAL);
                    assertThat(response.sources()).extracting(TutorSource::statementId)
                            .containsExactly(7L);
                })
                .verifyComplete();

        ArgumentCaptor<ChatSession> saved = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionRepository).save(saved.capture());
        assertThat(saved.getValue().getStatementId()).isNull();
        assertThat(saved.getValue().getSubjectId()).isEqualTo(SUBJECT);
        assertThat(saved.getValue().getTopicId()).isNull();
    }

    @Test
    void aTopicSessionKeepsTheTopicAndTheSubjectItBelongsTo() {
        when(subjectCatalog.read(SUBJECT, TOPIC)).thenReturn(Mono.just(material(TOPIC)));

        StepVerifier.create(service.createSession(ACCOUNT, ChatSessionRequest.ofSubject(SUBJECT, TOPIC)))
                .assertNext(response -> {
                    assertThat(response.subjectId()).isEqualTo(SUBJECT);
                    assertThat(response.topicId()).isEqualTo(TOPIC);
                    assertThat(response.statementId()).isNull();
                })
                .verifyComplete();

        ArgumentCaptor<ChatSession> saved = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionRepository).save(saved.capture());
        assertThat(saved.getValue().getTopicId()).isEqualTo(TOPIC);
        assertThat(saved.getValue().getSubjectId()).isEqualTo(SUBJECT);
    }

    @Test
    void aSessionHasOneContextOnly() {
        StepVerifier.create(service.createSession(ACCOUNT,
                        new ChatSessionRequest(7L, SUBJECT, null, null, null, null, null)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatusCode()).isEqualTo(400))
                .verify();

        StepVerifier.create(service.createSession(ACCOUNT,
                        new ChatSessionRequest(null, null, null, null, null, null, null)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatusCode()).isEqualTo(400))
                .verify();

        verifyNoInteractions(subjectCatalog);
        verify(sessionRepository, never()).save(any(ChatSession.class));
    }

    @Test
    void theModesAboutOneQuestionStayWithTheStatementTutor() {
        for (TutorMode mode : List.of(TutorMode.EXPLAIN_QUESTION, TutorMode.SIMILAR_EXERCISES)) {
            StepVerifier.create(service.createSession(ACCOUNT,
                            new ChatSessionRequest(null, SUBJECT, null, mode, null, null, null)))
                    .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatusCode()).isEqualTo(400))
                    .verify();
        }
        StepVerifier.create(service.createSession(ACCOUNT,
                        new ChatSessionRequest(null, SUBJECT, null, TutorMode.CONCEPT, 41L, null, null)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatusCode()).isEqualTo(400))
                .verify();

        verifyNoInteractions(subjectCatalog);
        verify(sessionRepository, never()).save(any(ChatSession.class));
    }

    @Test
    void aLiveSimulationOfTheSubjectKeepsItsTutorClosed() {
        when(subjectCatalog.read(SUBJECT, null)).thenReturn(Mono.just(material(null)));
        when(examGuard.requireNoLiveExamInSubject(ACCOUNT, SUBJECT))
                .thenReturn(Mono.error(new ApiException(HttpStatus.CONFLICT,
                        "Conflict", "A prova está a decorrer.", "CHAT_TUTOR_BLOCKED")));

        StepVerifier.create(service.createSession(ACCOUNT, ChatSessionRequest.ofSubject(SUBJECT, null)))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(409);
                    assertThat(((ApiException) error).getCode()).isEqualTo("CHAT_TUTOR_BLOCKED");
                })
                .verify();

        verify(sessionRepository, never()).save(any(ChatSession.class));
    }

    // === answering ========================================================

    @Test
    void answersFromTheSubjectCatalogAndNeverRevealsTheAnswerKey() {
        readyProvider();
        when(subjectCatalog.read(SUBJECT, null)).thenReturn(Mono.just(material(null)));

        StepVerifier.create(service.streamMessage(ACCOUNT, 55L, "como resolvo isto?").flatMapMany(f -> f))
                .expectNextCount(2) // delta + final
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroqMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(groqClient).streamChat(promptCaptor.capture());

        GroqMessage system = promptCaptor.getValue().get(0);
        assertThat(system.content())
                .contains("Kixi Tutor")
                .contains("nunca para revelar")
                .contains("DISCIPLINA: Matemática")
                .contains("MATERIAL DE ESTUDO")
                .contains("GABARITO (nunca revelar): B) 4")
                .contains("Exame Final de Matemática");
        verify(examGuard).requireNoLiveExamInSubject(ACCOUNT, SUBJECT);
    }

    @Test
    void aSimulationThatStartedAfterTheSessionClosesTheNextMessage() {
        ChatSession session = subjectSession();
        when(sessionRepository.findByIdAndAccountId(55L, ACCOUNT)).thenReturn(Mono.just(session));
        when(groqClient.isConfigured()).thenReturn(true);
        when(rateLimiter.check(ACCOUNT)).thenReturn(Mono.empty());
        when(subjectCatalog.read(SUBJECT, null)).thenReturn(Mono.just(material(null)));
        when(examGuard.requireNoLiveExamInSubject(ACCOUNT, SUBJECT))
                .thenReturn(Mono.error(new ApiException(HttpStatus.CONFLICT,
                        "Conflict", "A prova está a decorrer.", "CHAT_TUTOR_BLOCKED")));

        StepVerifier.create(service.streamMessage(ACCOUNT, 55L, "e agora?"))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatusCode()).isEqualTo(409))
                .verify();

        verify(messageRepository, never()).save(any(ChatMessage.class));
    }

    // === helpers ==========================================================

    private void readyProvider() {
        ChatSession session = subjectSession();
        when(sessionRepository.findByIdAndAccountId(55L, ACCOUNT)).thenReturn(Mono.just(session));
        when(groqClient.isConfigured()).thenReturn(true);
        when(rateLimiter.check(ACCOUNT)).thenReturn(Mono.empty());
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage saved = invocation.getArgument(0);
            saved.setId(saved.getRole() == ChatRole.USER ? 10L : 99L);
            return Mono.just(saved);
        });
        when(messageRepository.findBySessionIdOrderByCreatedAtDescIdDesc(55L)).thenReturn(Flux.empty());
        when(groqClient.streamChat(any())).thenReturn(Flux.just("Olá"));
        when(groqClient.getModel()).thenReturn("test-model");
    }

    private static ChatSession subjectSession() {
        ChatSession session = new ChatSession();
        session.setId(55L);
        session.setAccountId(ACCOUNT);
        session.setSubjectId(SUBJECT);
        session.setMode(TutorMode.GENERAL);
        return session;
    }

    private static TutorSubjectCatalog.Material material(Long topicId) {
        TutorSubjectCatalog.Question question = new TutorSubjectCatalog.Question(41L, 1, "Quanto é 2+2?",
                null, List.of(
                        new TutorSubjectCatalog.Choice("A", "3", false),
                        new TutorSubjectCatalog.Choice("B", "4", true)));
        TutorSubjectCatalog.StatementMaterial statement = new TutorSubjectCatalog.StatementMaterial(
                7L, "Exame Final de Matemática", List.of(question));
        return new TutorSubjectCatalog.Material(SUBJECT, "Matemática", topicId,
                topicId == null ? null : "Funções", List.of(statement));
    }
}
