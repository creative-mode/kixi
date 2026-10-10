package ao.creativemode.kixi.simulations.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.simulations.config.SimulationTimeLimitProperties;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Issue #107 (BE-11): the sweep that finishes the simulations whose time ran
 * out. The clock is the server's, so the simulations below are handed over
 * with the deadline already resolved by the deadline service — what this test
 * pins down is the sweep itself: who it closes, who it leaves alone, and that
 * one failure does not stop the rest.
 */
class SimulationExpirationJobTest {

    private SimulationRepository simulations;
    private SimulationDeadlineService deadlines;
    private SimulationSubmissionService submissions;
    private SimulationTimeLimitProperties properties;
    private SimulationExpirationJob job;

    @BeforeEach
    void setUp() {
        simulations = mock(SimulationRepository.class);
        deadlines = mock(SimulationDeadlineService.class);
        submissions = mock(SimulationSubmissionService.class);
        properties = new SimulationTimeLimitProperties();
        job = new SimulationExpirationJob(simulations, deadlines, submissions, properties);
    }

    @Test
    void closesTheSimulationWhoseTimeRanOut() {
        Simulation expired = simulation(1L);
        givenSweep(expired, true);

        job.closeExpiredSimulations();

        verify(submissions).submit(eq(1L), isNull(), eq(true));
    }

    @Test
    void leavesTheSimulationThatIsStillInTime() {
        givenSweep(simulation(1L), false);

        job.closeExpiredSimulations();

        verifyNoInteractions(submissions);
    }

    @Test
    void leavesTheSimulationWithoutADeadline() {
        givenSweep(simulation(1L), false);

        job.closeExpiredSimulations();

        verifyNoInteractions(submissions);
    }

    @Test
    void doesNotTouchTheFinishedSimulation() {
        Simulation finished = simulation(1L);
        finished.setStatus(SimulationStatus.FINISHED);
        when(simulations.findByStatusAndDeletedAtIsNull(SimulationStatus.IN_PROGRESS))
                .thenReturn(Flux.empty());

        job.closeExpiredSimulations();

        verifyNoInteractions(deadlines, submissions);
    }

    @Test
    void closesEveryExpiredSimulationOfTheSweep() {
        Simulation first = simulation(1L);
        Simulation second = simulation(2L);
        when(simulations.findByStatusAndDeletedAtIsNull(SimulationStatus.IN_PROGRESS))
                .thenReturn(Flux.just(first, second));
        when(deadlines.expired(eq(first), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(true));
        when(deadlines.expired(eq(second), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(true));
        when(submissions.submit(eq(1L), isNull(), eq(true))).thenReturn(Mono.just(first));
        when(submissions.submit(eq(2L), isNull(), eq(true))).thenReturn(Mono.just(second));

        job.closeExpiredSimulations();

        verify(submissions).submit(eq(1L), isNull(), eq(true));
        verify(submissions).submit(eq(2L), isNull(), eq(true));
    }

    /**
     * The paper the student submitted a second earlier answers 409 through the
     * claim inside submit. That must not take the sweep down with it, or every
     * other simulation would wait for the next poll.
     */
    @Test
    void aSimulationThatBlowsUpDoesNotStopTheSweep() {
        Simulation broken = simulation(1L);
        Simulation healthy = simulation(2L);
        when(simulations.findByStatusAndDeletedAtIsNull(SimulationStatus.IN_PROGRESS))
                .thenReturn(Flux.just(broken, healthy));
        when(deadlines.expired(eq(broken), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(true));
        when(deadlines.expired(eq(healthy), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(true));
        when(submissions.submit(eq(1L), isNull(), eq(true)))
                .thenReturn(Mono.error(new IllegalStateException("database is gone")));
        when(submissions.submit(eq(2L), isNull(), eq(true))).thenReturn(Mono.just(healthy));

        job.closeExpiredSimulations();

        verify(submissions).submit(eq(2L), isNull(), eq(true));
    }

    @Test
    void doesNothingWhenTheSweepIsDisabled() {
        properties.setExpiryEnabled(false);

        job.closeExpiredSimulations();

        verifyNoInteractions(simulations, deadlines, submissions);
    }

    @Test
    void doesNotPersistWhenSubmissionReturnsEmpty() {
        Simulation expired = simulation(1L);
        givenSweep(expired, true);
        when(submissions.submit(eq(1L), isNull(), eq(true))).thenReturn(Mono.empty());

        job.closeExpiredSimulations();

        verify(submissions).submit(eq(1L), isNull(), eq(true));
        verify(simulations, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private void givenSweep(Simulation simulation, boolean expired) {
        when(simulations.findByStatusAndDeletedAtIsNull(SimulationStatus.IN_PROGRESS))
                .thenReturn(Flux.just(simulation));
        when(deadlines.expired(eq(simulation), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(expired));
        if (expired) {
            when(submissions.submit(eq(simulation.getId()), isNull(), eq(true)))
                    .thenReturn(Mono.just(simulation));
        }
    }

    private Simulation simulation(Long id) {
        Simulation simulation = new Simulation();
        simulation.setId(id);
        simulation.setAccountId(42L);
        simulation.setStatus(SimulationStatus.IN_PROGRESS);
        return simulation;
    }

}
