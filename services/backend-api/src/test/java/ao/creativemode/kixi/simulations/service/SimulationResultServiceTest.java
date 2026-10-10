package ao.creativemode.kixi.simulations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.QuestionOption;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.ExamRoomAccess;
import ao.creativemode.kixi.simulations.dto.simulationresult.SimulationResultResponse;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SimulationResultServiceTest {

    private static final Long SIMULATION_ID = 50L;
    private static final Long STATEMENT_ID = 10L;
    private static final Long ACCOUNT_ID = 12L;
    private static final Long OTHER_ACCOUNT_ID = 99L;
    private static final Long QUESTION_ID = 20L;
    private static final Long OTHER_QUESTION_ID = 21L;
    private static final Long RIGHT_OPTION_ID = 31L;
    private static final Long WRONG_OPTION_ID = 30L;

    private SimulationRepository simulations;
    private SimulationAnswerRepository answers;
    private QuestionRepository questions;
    private QuestionOptionRepository options;
    private ExamRoomAccess examRooms;
    private SimulationResultService service;

    @BeforeEach
    void setUp() {
        simulations = mock(SimulationRepository.class);
        answers = mock(SimulationAnswerRepository.class);
        questions = mock(QuestionRepository.class);
        options = mock(QuestionOptionRepository.class);
        examRooms = mock(ExamRoomAccess.class);
        service = new SimulationResultService(simulations, answers, questions, options, examRooms);
    }

    // ── The gate ────────────────────────────────────────────────────────────

    @Test
    void thereIsNoResultWhileTheSimulationIsStillRunning() {
        // The whole point of the route: a result is something that happened, not
        // something you read while it is happening.
        givenASimulation(SimulationStatus.IN_PROGRESS);

        StepVerifier.create(service.findResult(SIMULATION_ID, ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT);
                    // The message says which, so the caller is not left guessing
                    // whether 409 meant this or that the simulation is gone.
                    assertThat(error.getMessage()).contains("finished").contains("IN_PROGRESS");
                })
                .verify();

        verifyNoInteractions(questions, options);
    }

    @Test
    void aCancelledSimulationHasNoResultEither() {
        givenASimulation(SimulationStatus.CANCELLED);

        StepVerifier.create(service.findResult(SIMULATION_ID, ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();
    }

    @Test
    void theGateHoldsForStaffToo() {
        // A teacher can already read the key on /statements/{id}/full. Letting
        // them read a half-done student's result here would only make the route
        // harder to reason about, and the students who are the point of the rule
        // are held either way.
        givenASimulationForAnyone(SimulationStatus.IN_PROGRESS);

        StepVerifier.create(service.findResult(SIMULATION_ID, OTHER_ACCOUNT_ID, true))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();
    }

    // ── Whose result ────────────────────────────────────────────────────────

    @Test
    void aStudentOnlyReachesTheirOwnSimulation() {
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(SIMULATION_ID, ACCOUNT_ID))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.findResult(SIMULATION_ID, ACCOUNT_ID, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verifyNoInteractions(questions);
    }

    @Test
    void staffReachAnySimulationWithoutAskingWhoseItIs() {
        givenAFinishedSimulationWithOneAnsweredQuestion();
        when(simulations.findByIdAndDeletedAtIsNull(SIMULATION_ID))
                .thenReturn(Mono.just(simulation(SimulationStatus.FINISHED)));

        StepVerifier.create(service.findResult(SIMULATION_ID, OTHER_ACCOUNT_ID, true))
                .assertNext(response -> assertThat(response.questions()).hasSize(1))
                .verifyComplete();

        verify(simulations, never())
                .findByIdAndAccountIdAndDeletedAtIsNull(anyLong(), anyLong());
    }

    // ── The result ──────────────────────────────────────────────────────────

    @Test
    void theResultCarriesTheKeyAndWhatTheStudentChose() {
        givenAFinishedSimulationWithOneAnsweredQuestion();

        StepVerifier.create(service.findResult(SIMULATION_ID, ACCOUNT_ID, false))
                .assertNext(response -> {
                    assertThat(response.simulationId()).isEqualTo(SIMULATION_ID);
                    assertThat(response.finalScore()).isEqualTo(5.0);
                    assertThat(response.questions()).hasSize(1);

                    SimulationResultResponse.QuestionResult question = response.questions().get(0);
                    // The answer key.
                    assertThat(question.correctOptionId()).isEqualTo(RIGHT_OPTION_ID);
                    assertThat(question.modelAnswer()).isEqualTo("because B is right");
                    // And what they did with it.
                    assertThat(question.selectedOptionId()).isEqualTo(WRONG_OPTION_ID);
                    assertThat(question.scoreObtained()).isEqualTo(0.0f);
                    assertThat(question.options())
                        .anySatisfy(option -> assertThat(option.isCorrect()).isTrue());
                })
                .verifyComplete();
    }

    @Test
    void aStudentResultInARunningRoomDoesNotExposeTheAnswerKey() {
        givenAFinishedSimulationWithOneAnsweredQuestion();
        Simulation simulation = simulation(SimulationStatus.FINISHED);
        simulation.setExamRoomId(77L);
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(SIMULATION_ID, ACCOUNT_ID))
                .thenReturn(Mono.just(simulation));
        when(examRooms.answerKeyVisible(77L)).thenReturn(Mono.just(false));

        StepVerifier.create(service.findResult(SIMULATION_ID, ACCOUNT_ID, false))
                .assertNext(response -> {
                    SimulationResultResponse.QuestionResult question = response.questions().get(0);
                    assertThat(question.modelAnswer()).isNull();
                    assertThat(question.correctOptionId()).isNull();
                    assertThat(question.isCorrect()).isNull();
                    assertThat(question.options()).allSatisfy(option -> assertThat(option.isCorrect()).isNull());
                })
                .verifyComplete();
    }

    @Test
    void aStudentResultInAClosedRoomExposesTheAnswerKey() {
        givenAFinishedSimulationWithOneAnsweredQuestion();
        Simulation simulation = simulation(SimulationStatus.FINISHED);
        simulation.setExamRoomId(77L);
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(SIMULATION_ID, ACCOUNT_ID))
                .thenReturn(Mono.just(simulation));
        when(examRooms.answerKeyVisible(77L)).thenReturn(Mono.just(true));

        StepVerifier.create(service.findResult(SIMULATION_ID, ACCOUNT_ID, false))
                .assertNext(response -> {
                    SimulationResultResponse.QuestionResult question = response.questions().get(0);
                    assertThat(question.modelAnswer()).isEqualTo("because B is right");
                    assertThat(question.correctOptionId()).isEqualTo(RIGHT_OPTION_ID);
                    assertThat(question.options()).anySatisfy(option -> assertThat(option.isCorrect()).isTrue());
                })
                .verifyComplete();
    }

    @Test
    void aQuestionWithNoMarkedAnswerCarriesNoAnswerKeyRatherThanGuessingOne() {
        // The approval gate asks for at least one correct option and only for
        // questions that have options at all, so an open question reaches here
        // with none. Its model answer stands on its own.
        givenASimulation(SimulationStatus.FINISHED);
        when(questions.findAllByStatementIdAndDeletedAtIsNull(STATEMENT_ID))
                .thenReturn(Flux.just(question(QUESTION_ID, "open", "Explique", "R = U/I")));
        when(answers.findAllBySimulationIdInAndDeletedAtIsNull(any())).thenReturn(Flux.empty());
        when(options.findAllByQuestionIdInAndDeletedAtIsNull(any())).thenReturn(Flux.empty());

        StepVerifier.create(service.findResult(SIMULATION_ID, ACCOUNT_ID, false))
                .assertNext(response -> {
                    SimulationResultResponse.QuestionResult question = response.questions().get(0);
                    assertThat(question.correctOptionId()).isNull();
                    assertThat(question.modelAnswer()).isEqualTo("R = U/I");
                    assertThat(question.selectedOptionId()).isNull();
                    assertThat(question.scoreObtained()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void aQuestionTheStudentNeverReachedStillShowsUp() {
        // The paper they sat, corrected. Leaving out what they skipped would make
        // the result shorter than the exam.
        givenASimulation(SimulationStatus.FINISHED);
        when(questions.findAllByStatementIdAndDeletedAtIsNull(STATEMENT_ID))
                .thenReturn(Flux.just(
                    question(QUESTION_ID, "multiple_choice", "Qual?", null),
                    question(OTHER_QUESTION_ID, "multiple_choice", "E outra?", null)));
        when(answers.findAllBySimulationIdInAndDeletedAtIsNull(any()))
                .thenReturn(Flux.just(answer(QUESTION_ID, RIGHT_OPTION_ID, 5.0f)));
        when(options.findAllByQuestionIdInAndDeletedAtIsNull(any()))
                .thenReturn(Flux.just(option(RIGHT_OPTION_ID, QUESTION_ID, "B", true)));

        StepVerifier.create(service.findResult(SIMULATION_ID, ACCOUNT_ID, false))
                .assertNext(response -> {
                    assertThat(response.questions()).hasSize(2);
                    SimulationResultResponse.QuestionResult skipped = response.questions().get(1);
                    assertThat(skipped.questionId()).isEqualTo(OTHER_QUESTION_ID);
                    assertThat(skipped.selectedOptionId()).isNull();
                    assertThat(skipped.scoreObtained()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void aStatementWithNoQuestionsAnswersEmptyRatherThanAskingForNothing() {
        // IN () with no ids is a syntax error in SQL, not an empty result.
        givenASimulation(SimulationStatus.FINISHED);
        when(questions.findAllByStatementIdAndDeletedAtIsNull(STATEMENT_ID)).thenReturn(Flux.empty());
        when(answers.findAllBySimulationIdInAndDeletedAtIsNull(any())).thenReturn(Flux.empty());

        StepVerifier.create(service.findResult(SIMULATION_ID, ACCOUNT_ID, false))
                .assertNext(response -> assertThat(response.questions()).isEmpty())
                .verifyComplete();

        verify(options, never()).findAllByQuestionIdInAndDeletedAtIsNull(any());
    }

    @Test
    void theOptionsOfTheWholePaperAreFetchedInOneQuery() {
        givenAFinishedSimulationWithOneAnsweredQuestion();

        StepVerifier.create(service.findResult(SIMULATION_ID, ACCOUNT_ID, false))
                .expectNextCount(1)
                .verifyComplete();

        // One round-trip for the paper, not one per question.
        verify(options).findAllByQuestionIdInAndDeletedAtIsNull(List.of(QUESTION_ID));
        verify(options, never()).findAllByQuestionIdAndDeletedAtIsNull(anyLong());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private void givenASimulation(SimulationStatus status) {
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(SIMULATION_ID, ACCOUNT_ID))
                .thenReturn(Mono.just(simulation(status)));
    }

    /** The query staff take, which does not ask whose simulation it is. */
    private void givenASimulationForAnyone(SimulationStatus status) {
        when(simulations.findByIdAndDeletedAtIsNull(SIMULATION_ID))
                .thenReturn(Mono.just(simulation(status)));
    }

    private void givenAFinishedSimulationWithOneAnsweredQuestion() {
        givenASimulation(SimulationStatus.FINISHED);
        when(questions.findAllByStatementIdAndDeletedAtIsNull(STATEMENT_ID))
                .thenReturn(Flux.just(
                    question(QUESTION_ID, "multiple_choice", "Qual?", "because B is right")));
        when(answers.findAllBySimulationIdInAndDeletedAtIsNull(any()))
                .thenReturn(Flux.just(answer(QUESTION_ID, WRONG_OPTION_ID, 0.0f)));
        when(options.findAllByQuestionIdInAndDeletedAtIsNull(any()))
                .thenReturn(Flux.just(
                    option(WRONG_OPTION_ID, QUESTION_ID, "A", false),
                    option(RIGHT_OPTION_ID, QUESTION_ID, "B", true)));
    }

    private Simulation simulation(SimulationStatus status) {
        Simulation simulation = new Simulation();
        simulation.setId(SIMULATION_ID);
        simulation.setAccountId(ACCOUNT_ID);
        simulation.setStatementId(STATEMENT_ID);
        simulation.setSchoolYearId(3L);
        simulation.setStatus(status);
        simulation.setStartedAt(LocalDateTime.now().minusHours(1));
        if (SimulationStatus.FINISHED == status) {
            simulation.setFinishedAt(LocalDateTime.now());
            simulation.setFinalScore(5.0);
            simulation.setTimeSpentSeconds(1800);
        }
        return simulation;
    }

    private Question question(Long id, String type, String text, String modelAnswer) {
        Question question = new Question(STATEMENT_ID, 1, text, type);
        question.setId(id);
        question.setMaxScore(5.0);
        question.setModelAnswer(modelAnswer);
        return question;
    }

    private QuestionOption option(Long id, Long questionId, String label, boolean correct) {
        QuestionOption option = new QuestionOption(questionId, label, label + " text", correct);
        option.setId(id);
        return option;
    }

    private SimulationAnswer answer(Long questionId, Long selectedOptionId, Float score) {
        SimulationAnswer answer = new SimulationAnswer();
        answer.setId(100L);
        answer.setSimulationId(SIMULATION_ID);
        answer.setQuestionId(questionId);
        answer.setSelectedOptionId(selectedOptionId);
        answer.setScoreObtained(score);
        answer.setAnsweredAt(LocalDateTime.now());
        return answer;
    }
}
