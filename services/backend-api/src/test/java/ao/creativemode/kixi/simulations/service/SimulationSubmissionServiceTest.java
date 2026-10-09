package ao.creativemode.kixi.simulations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import ao.creativemode.kixi.simulations.model.SimulationAnswerStatus;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SimulationSubmissionServiceTest {

    private SimulationRepository simulations;
    private SimulationAnswerRepository answers;
    private QuestionRepository questions;
    private QuestionOptionRepository options;
    private SimulationSubmissionService service;

    @BeforeEach
    void setUp() {
        simulations = mock(SimulationRepository.class);
        answers = mock(SimulationAnswerRepository.class);
        questions = mock(QuestionRepository.class);
        options = mock(QuestionOptionRepository.class);
        service = new SimulationSubmissionService(simulations, answers, questions, options);
        when(simulations.claimSubmission(10L)).thenReturn(Mono.just(1));
    }

    @Test
    void scoresAllCorrectAnswersAndUsesServerTime() {
        Simulation simulation = simulation(SimulationStatus.IN_PROGRESS);
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(10L, 7L)).thenReturn(Mono.just(simulation));
        when(questions.findAllByStatementIdAndDeletedAtIsNull(20L)).thenReturn(Flux.just(question(1L, 2), question(2L, 3)));
        when(answers.findAllBySimulationIdInAndDeletedAtIsNull(List.of(10L)))
                .thenReturn(Flux.just(answer(1L, 101L), answer(2L, 201L)));
        when(options.findAllByQuestionIdInAndDeletedAtIsNull(anyCollection()))
                .thenReturn(Flux.just(option(101L, 1L, true), option(201L, 2L, true)));
        when(answers.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(simulations.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

        StepVerifier.create(service.submit(10L, 7L, false))
                .assertNext(result -> {
                    assertThat(result.getFinalScore()).isEqualTo(5.0);
                    assertThat(result.getStatus()).isEqualTo(SimulationStatus.FINISHED);
                    assertThat(result.getTimeSpentSeconds()).isNotNegative();
                }).verifyComplete();
        verify(answers).save(org.mockito.ArgumentMatchers.argThat(a ->
                a.getQuestionId().equals(1L) && a.getIsCorrect() && a.getScoreObtained() == 2f));
    }

    @Test
    void scoresPartialAnswersAndLeavesOpenQuestionPendingReview() {
        Simulation simulation = simulation(SimulationStatus.IN_PROGRESS);
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(10L, 7L)).thenReturn(Mono.just(simulation));
        Question choice = question(1L, 2);
        Question open = question(2L, 4);
        open.setQuestionType("development");
        when(questions.findAllByStatementIdAndDeletedAtIsNull(20L)).thenReturn(Flux.just(choice, open));
        when(answers.findAllBySimulationIdInAndDeletedAtIsNull(List.of(10L)))
                .thenReturn(Flux.just(answer(1L, 102L), answer(2L, null)));
        when(options.findAllByQuestionIdInAndDeletedAtIsNull(anyCollection()))
                .thenReturn(Flux.just(option(101L, 1L, true), option(102L, 1L, false)));
        when(answers.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(simulations.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

        StepVerifier.create(service.submit(10L, 7L, false))
                .assertNext(result -> assertThat(result.getFinalScore()).isEqualTo(0.0))
                .verifyComplete();
        verify(answers).save(org.mockito.ArgumentMatchers.argThat(a ->
                a.getQuestionId().equals(2L)
                        && a.getReviewStatus() == SimulationAnswerStatus.PENDING_REVIEW
                        && a.getScoreObtained() == null));
    }

    @Test
    void persistsBlankObjectiveAnswersAsIncorrectZero() {
        Simulation simulation = simulation(SimulationStatus.IN_PROGRESS);
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(10L, 7L)).thenReturn(Mono.just(simulation));
        when(questions.findAllByStatementIdAndDeletedAtIsNull(20L)).thenReturn(Flux.just(question(1L, 2)));
        when(answers.findAllBySimulationIdInAndDeletedAtIsNull(List.of(10L))).thenReturn(Flux.empty());
        when(options.findAllByQuestionIdInAndDeletedAtIsNull(anyCollection()))
                .thenReturn(Flux.just(option(101L, 1L, true)));
        when(answers.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(simulations.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

        StepVerifier.create(service.submit(10L, 7L, false))
                .assertNext(result -> assertThat(result.getFinalScore()).isEqualTo(0.0))
                .verifyComplete();
        verify(answers).save(org.mockito.ArgumentMatchers.argThat(a ->
                a.getQuestionId().equals(1L) && Boolean.FALSE.equals(a.getIsCorrect())
                        && a.getScoreObtained() == 0f));
    }

    @Test
    void rejectsDoubleSubmission() {
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(10L, 7L))
                .thenReturn(Mono.just(simulation(SimulationStatus.FINISHED)));
        StepVerifier.create(service.submit(10L, 7L, false))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();
        verify(answers, never()).save(any());
    }

    @Test
    void doesNotAllowAStudentToSubmitSomebodyElsesSimulation() {
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(10L, 7L)).thenReturn(Mono.empty());
        StepVerifier.create(service.submit(10L, 7L, false))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();
        verify(simulations, never()).save(any());
    }

    private Simulation simulation(SimulationStatus status) {
        Simulation simulation = new Simulation();
        simulation.setId(10L);
        simulation.setAccountId(7L);
        simulation.setStatementId(20L);
        simulation.setStartedAt(LocalDateTime.now().minusMinutes(3));
        simulation.setStatus(status);
        return simulation;
    }

    private Question question(Long id, int maxScore) {
        Question question = new Question();
        question.setId(id);
        question.setMaxScore((double) maxScore);
        return question;
    }

    private SimulationAnswer answer(Long questionId, Long optionId) {
        SimulationAnswer answer = new SimulationAnswer();
        answer.setSimulationId(10L);
        answer.setQuestionId(questionId);
        answer.setSelectedOptionId(optionId);
        return answer;
    }

    private QuestionOption option(Long id, Long questionId, boolean correct) {
        QuestionOption option = new QuestionOption();
        option.setId(id);
        option.setQuestionId(questionId);
        option.setIsCorrect(correct);
        return option;
    }
}
