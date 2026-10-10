package ao.creativemode.kixi.simulations.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationAnswer;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationAnswerRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import ao.creativemode.kixi.simulations.service.SimulationAnswerService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SimulationAnswerControllerTest {

    @Test
    void studentCannotUseAnswerLifecycleRoutes() {
        CurrentAccountService current = mock(CurrentAccountService.class);
        SimulationAnswerService service = realAnswerService();
        when(current.requiredAccountId()).thenReturn(Mono.just(7L));
        when(current.hasAnyRole("ADMIN")).thenReturn(Mono.just(false));
        when(current.hasAnyRole("TEACHER")).thenReturn(Mono.just(false));
        SimulationAnswerController controller = new SimulationAnswerController(service, current, null);

        StepVerifier.create(controller.softDelete(10L))
                .expectErrorMatches(error -> error.getMessage().contains("Only ADMIN"))
                .verify();
    }

    @Test
    void adminStillCannotPurgeAnAnswerAfterFinished() {
        CurrentAccountService current = mock(CurrentAccountService.class);
        SimulationAnswerRepository answers = mock(SimulationAnswerRepository.class);
        SimulationRepository simulations = mock(SimulationRepository.class);
        SimulationAnswer answer = new SimulationAnswer();
        answer.setId(10L);
        answer.setSimulationId(20L);
        answer.markAsDeleted();
        Simulation finished = new Simulation();
        finished.setId(20L);
        finished.setStatus(SimulationStatus.FINISHED);
        when(current.requiredAccountId()).thenReturn(Mono.just(99L));
        when(current.hasAnyRole("ADMIN")).thenReturn(Mono.just(true));
        when(current.hasAnyRole("TEACHER")).thenReturn(Mono.just(false));
        when(answers.findByIdAndDeletedAtIsNotNull(10L)).thenReturn(Mono.just(answer));
        when(answers.findById(10L)).thenReturn(Mono.just(answer));
        when(answers.delete(answer)).thenReturn(Mono.empty());
        when(simulations.findById(20L)).thenReturn(Mono.just(finished));
        when(simulations.lockForAnswerWriteByAnswerId(10L)).thenReturn(Mono.just(finished));
        SimulationAnswerService service = new SimulationAnswerService(answers, simulations,
                mock(QuestionRepository.class), mock(QuestionOptionRepository.class),
                mock(ao.creativemode.kixi.simulations.service.SimulationDeadlineService.class), null);
        SimulationAnswerController controller = new SimulationAnswerController(service, current, null);

        StepVerifier.create(controller.hardDelete(10L))
                .expectErrorSatisfies(error -> assertThat(error.getMessage()).contains("finished"))
                .verify();
    }

    private SimulationAnswerService realAnswerService() {
        return new SimulationAnswerService(mock(SimulationAnswerRepository.class),
                mock(SimulationRepository.class), mock(QuestionRepository.class),
                mock(QuestionOptionRepository.class),
                mock(ao.creativemode.kixi.simulations.service.SimulationDeadlineService.class), null);
    }
}
