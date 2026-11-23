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

        StepVerifier.create(controller.softDelete(10L, "duplicada"))
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

        StepVerifier.create(controller.hardDelete(10L, "duplicada", "10"))
                .expectErrorSatisfies(error -> assertThat(error.getMessage()).contains("finished"))
                .verify();
    }

    @Test
    void purgeWithoutTheRightConfirmationIsRejectedBeforeAnyChange() {
        CurrentAccountService current = mock(CurrentAccountService.class);
        SimulationAnswerRepository answers = mock(SimulationAnswerRepository.class);
        SimulationAnswerController controller = new SimulationAnswerController(
                new SimulationAnswerService(answers, mock(SimulationRepository.class), mock(QuestionRepository.class),
                        mock(QuestionOptionRepository.class),
                        mock(ao.creativemode.kixi.simulations.service.SimulationDeadlineService.class), null),
                current, null);

        for (String confirm : new String[] {null, "", "11", "purge"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.hardDelete(10L, "pedido do aluno", confirm))
                    .hasMessageContaining("confirm=10");
        }
        org.mockito.Mockito.verifyNoInteractions(answers);
    }

    @Test
    void teacherCannotDeleteAnAnswer() {
        CurrentAccountService current = mock(CurrentAccountService.class);
        when(current.requiredAccountId()).thenReturn(Mono.just(7L));
        when(current.hasAnyRole("ADMIN")).thenReturn(Mono.just(false));
        SimulationAnswerController controller = new SimulationAnswerController(realAnswerService(), current, null);

        StepVerifier.create(controller.softDelete(10L, "duplicada"))
                .expectErrorMatches(e -> e.getMessage().contains("Only ADMIN"))
                .verify();
    }

    @Test
    void deleteWithoutAReasonIsRejectedBeforeAnyChange() {
        CurrentAccountService current = mock(CurrentAccountService.class);
        SimulationAnswerRepository answers = mock(SimulationAnswerRepository.class);
        SimulationAnswerController controller = new SimulationAnswerController(
                new SimulationAnswerService(answers, mock(SimulationRepository.class), mock(QuestionRepository.class),
                        mock(QuestionOptionRepository.class),
                        mock(ao.creativemode.kixi.simulations.service.SimulationDeadlineService.class), null),
                current, null);

        for (String reason : new String[] {null, "", "  ", "abc"}) {
            StepVerifier.create(controller.softDelete(10L, reason))
                    .expectErrorMatches(e -> e.getMessage().contains("motivo"))
                    .verify();
        }
        org.mockito.Mockito.verifyNoInteractions(answers);
    }

    @Test
    void adminDeleteIsAuditedWithWhoWhyAndWhich() {
        CurrentAccountService current = mock(CurrentAccountService.class);
        SimulationAnswerRepository answers = mock(SimulationAnswerRepository.class);
        SimulationRepository simulations = mock(SimulationRepository.class);
        SimulationAnswer answer = new SimulationAnswer();
        answer.setId(10L);
        answer.setSimulationId(20L);
        Simulation running = new Simulation();
        running.setId(20L);
        running.setStatus(SimulationStatus.IN_PROGRESS);
        when(current.requiredAccountId()).thenReturn(Mono.just(99L));
        when(current.hasAnyRole("ADMIN")).thenReturn(Mono.just(true));
        when(answers.findById(10L)).thenReturn(Mono.just(answer));
        when(answers.findByIdAndDeletedAtIsNull(10L)).thenReturn(Mono.just(answer));
        when(answers.save(answer)).thenReturn(Mono.just(answer));
        when(simulations.findById(20L)).thenReturn(Mono.just(running));
        when(simulations.lockForAnswerWriteByAnswerId(10L)).thenReturn(Mono.just(running));
        var audit = mock(ao.creativemode.kixi.simulations.service.SimulationAuditService.class);
        when(audit.record(ao.creativemode.kixi.simulations.service.SimulationAuditService.Target.ANSWER, 10L, 20L,
                ao.creativemode.kixi.simulations.service.SimulationAuditService.Action.SOFT_DELETE, 99L, "duplicada")).thenReturn(Mono.empty());
        var deadline = mock(ao.creativemode.kixi.simulations.service.SimulationDeadlineService.class);
        when(deadline.expired(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(false));
        SimulationAnswerService service = new SimulationAnswerService(answers, simulations, mock(QuestionRepository.class),
                mock(QuestionOptionRepository.class), deadline, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "audit", audit);
        SimulationAnswerController controller = new SimulationAnswerController(service, current, null);

        StepVerifier.create(controller.softDelete(10L, "  duplicada ")).expectNextCount(1).verifyComplete();

        org.mockito.Mockito.verify(audit).record(ao.creativemode.kixi.simulations.service.SimulationAuditService.Target.ANSWER,
                10L, 20L, ao.creativemode.kixi.simulations.service.SimulationAuditService.Action.SOFT_DELETE, 99L, "duplicada");
    }

    private SimulationAnswerService realAnswerService() {
        return new SimulationAnswerService(mock(SimulationAnswerRepository.class),
                mock(SimulationRepository.class), mock(QuestionRepository.class),
                mock(QuestionOptionRepository.class),
                mock(ao.creativemode.kixi.simulations.service.SimulationDeadlineService.class), null);
    }
}
