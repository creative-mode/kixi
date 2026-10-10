package ao.creativemode.kixi.simulations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.simulations.dto.simulationanswer.SimulationAnswerRequest;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.simulations.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Regression coverage for a bug found via manual testing: creating an answer
 * with a nonexistent simulationId/questionId used to hit the database's
 * foreign key constraint and get reported back as "already exists" (409) -
 * a duplicate-resource message for what was actually a missing-reference
 * error. SimulationAnswerService now validates both references before ever
 * touching the repository, and reserves the 409 for a real duplicate
 * (simulation_id, question_id) pair.
 */
class SimulationAnswerServiceTest {

    private SimulationAnswerRepository repository;
    private SimulationRepository simulationRepository;
    private QuestionRepository questionRepository;
    private SimulationDeadlineService deadlineService;
    private SimulationAnswerService service;

    @BeforeEach
    void setUp() {
        repository = mock(SimulationAnswerRepository.class);
        simulationRepository = mock(SimulationRepository.class);
        questionRepository = mock(QuestionRepository.class);
        deadlineService = mock(SimulationDeadlineService.class);
        when(deadlineService.expired(any(Simulation.class), any())).thenReturn(Mono.just(false));
        when(simulationRepository.lockForAnswerWrite(any())).thenReturn(Mono.just(inProgressSimulation()));
        service = new SimulationAnswerService(repository, simulationRepository, questionRepository,
                deadlineService);
    }

    @Test
    void createRejectsNonexistentSimulationWithoutTouchingRepository() {
        when(simulationRepository.findByIdAndDeletedAtIsNull(9999L)).thenReturn(Mono.empty());
        // requireSimulationAndQuestion() builds its .then(questionRepository.findById(...))
        // argument eagerly, before the simulation check's emptiness is even known, so the
        // question mock still needs a stub even though the simulation error wins.
        when(questionRepository.findById(1L)).thenReturn(Mono.just(new Question()));

        StepVerifier.create(service.create(new SimulationAnswerRequest(9999L, 1L, null, null, null)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage())
                            .isEqualTo("Simulation not found: 9999");
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsNonexistentQuestionWithoutTouchingRepository() {
        when(simulationRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(inProgressSimulation()));
        when(questionRepository.findById(9999L)).thenReturn(Mono.empty());

        StepVerifier.create(service.create(new SimulationAnswerRequest(1L, 9999L, null, null, null)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage())
                            .isEqualTo("Question not found: 9999");
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void createSavesAnswerWhenSimulationAndQuestionExist() {
        when(simulationRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(inProgressSimulation()));
        when(questionRepository.findById(1L)).thenReturn(Mono.just(new Question()));
        when(repository.insertIfInProgress(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            SimulationAnswer saved = answer(7L);
            saved.setSimulationId(invocation.getArgument(0));
            saved.setQuestionId(invocation.getArgument(1));
            return Mono.just(saved);
        });

        StepVerifier.create(service.create(new SimulationAnswerRequest(1L, 1L, 3L, null, null)))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(7L);
                    assertThat(response.simulationId()).isEqualTo(1L);
                    assertThat(response.questionId()).isEqualTo(1L);
                })
                .verifyComplete();

        InOrder order = inOrder(simulationRepository, repository);
        order.verify(simulationRepository).lockForAnswerWrite(1L);
        order.verify(repository).insertIfInProgress(any(), any(), any(), any(), any());
    }

    @Test
    void createRejectsAnswersForFinishedSimulation() {
        Simulation finished = inProgressSimulation();
        finished.setStatus(SimulationStatus.FINISHED);
        when(simulationRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(finished));
        when(questionRepository.findById(1L)).thenReturn(Mono.just(new Question()));

        StepVerifier.create(service.create(new SimulationAnswerRequest(1L, 1L, null, null, null)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(409);
                })
                .verify();
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsQuestionFromAnotherStatementBeforeWriting() {
        QuestionOptionRepository options = mock(QuestionOptionRepository.class);
        SimulationAnswerService scopedService = new SimulationAnswerService(repository, simulationRepository,
                questionRepository, options, deadlineService, null);
        Simulation simulation = inProgressSimulation();
        simulation.setStatementId(10L);
        Question question = new Question();
        question.setId(1L);
        question.setStatementId(11L);
        when(simulationRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(simulation));
        when(questionRepository.findById(1L)).thenReturn(Mono.just(question));

        StepVerifier.create(scopedService.create(new SimulationAnswerRequest(1L, 1L, null, null, null)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();
        verify(repository, never()).insertIfInProgress(any(), any(), any(), any(), any());
    }

    @Test
    void createRejectsExpiredSimulationBeforeWriting() {
        when(simulationRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(inProgressSimulation()));
        when(questionRepository.findById(1L)).thenReturn(Mono.just(new Question()));
        when(deadlineService.expired(any(Simulation.class), any())).thenReturn(Mono.just(true));

        StepVerifier.create(service.create(new SimulationAnswerRequest(1L, 1L, null, null, null)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).insertIfInProgress(any(), any(), any(), any(), any());
    }

    @Test
    void createReportsRealDuplicateAsConflictAfterReferencesAreValid() {
        when(simulationRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(inProgressSimulation()));
        when(questionRepository.findById(1L)).thenReturn(Mono.just(new Question()));
        when(repository.insertIfInProgress(any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new DataIntegrityViolationException("duplicate key")));

        StepVerifier.create(service.create(new SimulationAnswerRequest(1L, 1L, null, null, null)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getMessage())
                            .isEqualTo("This question has already been answered in this simulation.");
                })
                .verify();
    }

    @Test
    void findAllActiveMapsEveryEntityToResponse() {
        when(repository.findAllByDeletedAtIsNull()).thenReturn(Flux.just(answer(1L)));

        StepVerifier.create(service.findAllActive())
                .assertNext(response -> assertThat(response.id()).isEqualTo(1L))
                .verifyComplete();
    }

    @Test
    void findAllDeletedReturnsOnlyTrashedEntities() {
        when(repository.findAllByDeletedAtIsNotNull()).thenReturn(Flux.just(answer(2L)));

        StepVerifier.create(service.findAllDeleted())
                .assertNext(response -> assertThat(response.id()).isEqualTo(2L))
                .verifyComplete();
    }

    @Test
    void findByIdActiveReturnsNotFoundForMissingAnswer() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findByIdActive(99L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatusCode()).isEqualTo(404);
                })
                .verify();
    }

    @Test
    void updateRejectsMissingAnswerWithoutSaving() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(99L, new SimulationAnswerRequest(1L, 1L, null, null, null)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void updateAppliesNewFieldsToExistingAnswer() {
        SimulationAnswer existing = answer(1L);
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(simulationRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(inProgressSimulation()));
        when(questionRepository.findById(1L)).thenReturn(Mono.just(new Question()));
        when(repository.updateIfInProgress(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    existing.setSelectedOptionId(invocation.getArgument(4));
                    return Mono.just(existing);
                });

        StepVerifier.create(service.update(1L, new SimulationAnswerRequest(1L, 1L, 2L, "resposta", null)))
                .assertNext(response -> assertThat(response.selectedOptionId()).isEqualTo(2L))
                .verifyComplete();
    }

    @Test
    void updateRejectsExpiredSimulationBeforeWriting() {
        SimulationAnswer existing = answer(1L);
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(simulationRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(inProgressSimulation()));
        when(questionRepository.findById(1L)).thenReturn(Mono.just(new Question()));
        when(deadlineService.expired(any(Simulation.class), any())).thenReturn(Mono.just(true));

        StepVerifier.create(service.update(1L, new SimulationAnswerRequest(1L, 1L, null, null, null)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).updateIfInProgress(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void softDeleteRejectsMissingAnswer() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.softDelete(99L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void softDeleteMarksEntityAsDeleted() {
        SimulationAnswer existing = answer(1L);
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(existing));
        when(repository.save(existing)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.softDelete(1L)).verifyComplete();

        assertThat(existing.isDeleted()).isTrue();
    }

    @Test
    void restoreRejectsAnswerThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.restore(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void restoreClearsDeletedAt() {
        SimulationAnswer deleted = answer(1L);
        deleted.markAsDeleted();
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.save(deleted)).thenReturn(Mono.just(deleted));

        StepVerifier.create(service.restore(1L)).verifyComplete();

        assertThat(deleted.isDeleted()).isFalse();
    }

    @Test
    void hardDeleteRejectsAnswerThatIsNotInTrash() {
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ApiException.class))
                .verify();

        verify(repository, never()).delete(any(SimulationAnswer.class));
    }

    @Test
    void hardDeleteRemovesTrashedAnswer() {
        SimulationAnswer deleted = answer(1L);
        deleted.markAsDeleted();
        when(repository.findByIdAndDeletedAtIsNotNull(1L)).thenReturn(Mono.just(deleted));
        when(repository.delete(deleted)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(1L)).verifyComplete();

        verify(repository).delete(deleted);
    }

    private SimulationAnswer answer(Long id) {
        SimulationAnswer answer = new SimulationAnswer();
        answer.setId(id);
        answer.setSimulationId(1L);
        answer.setQuestionId(1L);
        return answer;
    }

    private Simulation inProgressSimulation() {
        Simulation simulation = new Simulation();
        simulation.setStatus(SimulationStatus.IN_PROGRESS);
        return simulation;
    }
}
