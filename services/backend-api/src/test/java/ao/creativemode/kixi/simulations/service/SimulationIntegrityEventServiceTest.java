package ao.creativemode.kixi.simulations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.simulations.dto.integrity.SimulationIntegrityEventRequest;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationIntegrityEvent;
import ao.creativemode.kixi.simulations.model.SimulationIntegrityEventKind;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationIntegrityEventRepository;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SimulationIntegrityEventServiceTest {
    private SimulationRepository simulations;
    private SimulationIntegrityEventRepository events;
    private QuestionRepository questions;
    private SimulationIntegrityEventService service;
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 21, 10, 0);

    @BeforeEach
    void setUp() {
        simulations = org.mockito.Mockito.mock(SimulationRepository.class);
        events = org.mockito.Mockito.mock(SimulationIntegrityEventRepository.class);
        questions = org.mockito.Mockito.mock(QuestionRepository.class);
        service = new SimulationIntegrityEventService(simulations, events, questions,
                Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void recordsOnlyTheOwnersActiveSimulationAndUsesServerTime() {
        Simulation simulation = simulation(SimulationStatus.IN_PROGRESS);
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(12L, 7L)).thenReturn(Mono.just(simulation));
        when(events.countBySimulationIdAndAtAfter(12L, now.minusMinutes(1))).thenReturn(Mono.just(0L));
        when(events.save(any(SimulationIntegrityEvent.class))).thenAnswer(invocation -> {
            SimulationIntegrityEvent event = invocation.getArgument(0);
            event.setId(31L);
            return Mono.just(event);
        });

        StepVerifier.create(service.record(12L, 7L,
                        new SimulationIntegrityEventRequest(SimulationIntegrityEventKind.TAB_HIDDEN, null)))
                .assertNext(event -> {
                    assertThat(event.id()).isEqualTo(31L);
                    assertThat(event.simulationId()).isEqualTo(12L);
                    assertThat(event.at()).isEqualTo(now);
                    assertThat(event.kind()).isEqualTo(SimulationIntegrityEventKind.TAB_HIDDEN);
                    assertThat(event.questionId()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void rejectsQuestionFromAnotherStatementAndDoesNotPersist() {
        Simulation simulation = simulation(SimulationStatus.IN_PROGRESS);
        Question question = new Question();
        question.setStatementId(88L);
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(12L, 7L)).thenReturn(Mono.just(simulation));
        when(questions.findByIdAndDeletedAtIsNull(4L)).thenReturn(Mono.just(question));

        StepVerifier.create(service.record(12L, 7L,
                        new SimulationIntegrityEventRequest(SimulationIntegrityEventKind.PASTE_BLOCKED, 4L)))
                .expectErrorMatches(error -> error instanceof ao.creativemode.kixi.shared.exception.ApiException api
                        && api.getStatusCode() == 400)
                .verify();
        verify(events, never()).save(any());
    }

    @Test
    void rejectsEventsAfterSimulationEnds() {
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(12L, 7L))
                .thenReturn(Mono.just(simulation(SimulationStatus.FINISHED)));

        StepVerifier.create(service.record(12L, 7L,
                        new SimulationIntegrityEventRequest(SimulationIntegrityEventKind.TAB_HIDDEN, null)))
                .expectErrorMatches(error -> error instanceof ao.creativemode.kixi.shared.exception.ApiException api
                        && api.getStatusCode() == 409)
                .verify();
        verify(events, never()).save(any());
    }

    @Test
    void rejectsQuestionIdsOnNonPasteEvents() {
        StepVerifier.create(service.record(12L, 7L,
                        new SimulationIntegrityEventRequest(SimulationIntegrityEventKind.TAB_HIDDEN, 4L)))
                .expectErrorMatches(error -> error instanceof ao.creativemode.kixi.shared.exception.ApiException api
                        && api.getStatusCode() == 400)
                .verify();
        verify(simulations, never()).findByIdAndAccountIdAndDeletedAtIsNull(any(), any());
    }

    @Test
    void requiresQuestionIdForPasteEvents() {
        StepVerifier.create(service.record(12L, 7L,
                        new SimulationIntegrityEventRequest(SimulationIntegrityEventKind.PASTE_BLOCKED, null)))
                .expectErrorMatches(error -> error instanceof ao.creativemode.kixi.shared.exception.ApiException api
                        && api.getStatusCode() == 400)
                .verify();
        verify(simulations, never()).findByIdAndAccountIdAndDeletedAtIsNull(any(), any());
    }

    @Test
    void capsEventVolumePerAttempt() {
        when(simulations.findByIdAndAccountIdAndDeletedAtIsNull(12L, 7L))
                .thenReturn(Mono.just(simulation(SimulationStatus.IN_PROGRESS)));
        when(events.countBySimulationIdAndAtAfter(12L, now.minusMinutes(1))).thenReturn(Mono.just(120L));

        StepVerifier.create(service.record(12L, 7L,
                        new SimulationIntegrityEventRequest(SimulationIntegrityEventKind.TAB_HIDDEN, null)))
                .expectErrorMatches(error -> error instanceof ao.creativemode.kixi.shared.exception.ApiException api
                        && api.getStatusCode() == 429)
                .verify();
        verify(events, never()).save(any());
    }

    private Simulation simulation(SimulationStatus status) {
        Simulation simulation = new Simulation();
        simulation.setId(12L);
        simulation.setStatementId(77L);
        simulation.setStatus(status);
        return simulation;
    }
}
