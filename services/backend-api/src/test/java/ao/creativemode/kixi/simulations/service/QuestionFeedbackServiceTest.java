package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.ExamRoomAccess;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A correção no treino (kixi#729): só uma simulação livre, do próprio aluno, em curso, sem sala ativa. */
class QuestionFeedbackServiceTest {

    private final SimulationRepository simulations = mock(SimulationRepository.class);
    private final QuestionRepository questions = mock(QuestionRepository.class);
    private final QuestionOptionRepository options = mock(QuestionOptionRepository.class);
    private final ExamRoomAccess rooms = mock(ExamRoomAccess.class);
    private QuestionFeedbackService service;

    @BeforeEach
    void setUp() {
        service = new QuestionFeedbackService(simulations, questions, options, rooms);
        Question q = new Question();
        q.setId(7L);
        q.setStatementId(5L);
        q.setModelAnswer("porque sim");
        when(questions.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(q));
        QuestionOption right = new QuestionOption();
        right.setId(71L);
        right.setIsCorrect(true);
        QuestionOption wrong = new QuestionOption();
        wrong.setId(72L);
        wrong.setIsCorrect(false);
        when(options.findAllByQuestionIdAndDeletedAtIsNull(7L)).thenReturn(Flux.just(right, wrong));
        when(rooms.hasNonClosedRoom(5L)).thenReturn(Mono.just(false));
    }

    private Simulation simulation(Long accountId, Long roomId, SimulationStatus status) {
        Simulation s = new Simulation();
        s.setId(1L);
        s.setAccountId(accountId);
        s.setStatementId(5L);
        s.setExamRoomId(roomId);
        s.setStatus(status);
        when(simulations.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(s));
        return s;
    }

    @Test
    void telsIfTheChosenOptionIsRight() {
        simulation(42L, null, SimulationStatus.IN_PROGRESS);
        StepVerifier.create(service.check(1L, 7L, 71L, 42L))
                .assertNext(f -> { assertThat(f.correct()).isTrue(); assertThat(f.correctOptionId()).isEqualTo(71L); })
                .verifyComplete();
        StepVerifier.create(service.check(1L, 7L, 72L, 42L))
                .assertNext(f -> { assertThat(f.correct()).isFalse(); assertThat(f.correctOptionId()).isEqualTo(71L); })
                .verifyComplete();
    }

    @Test
    void anExamRoomNeverShowsTheKey() {
        simulation(42L, 9L, SimulationStatus.IN_PROGRESS);
        StepVerifier.create(service.check(1L, 7L, 71L, 42L)).expectError(ApiException.class).verify();
    }

    @Test
    void someoneElsesSimulationIsNotFound() {
        simulation(42L, null, SimulationStatus.IN_PROGRESS);
        StepVerifier.create(service.check(1L, 7L, 71L, 99L)).expectError(ApiException.class).verify();
    }

    @Test
    void aFinishedSimulationIsRefused() {
        simulation(42L, null, SimulationStatus.FINISHED);
        StepVerifier.create(service.check(1L, 7L, 71L, 42L)).expectError(ApiException.class).verify();
    }

    @Test
    void anActiveRoomOnTheStatementBlocksTheCheck() {
        simulation(42L, null, SimulationStatus.IN_PROGRESS);
        when(rooms.hasNonClosedRoom(5L)).thenReturn(Mono.just(true));
        StepVerifier.create(service.check(1L, 7L, 71L, 42L)).expectError(ApiException.class).verify();
    }
}
