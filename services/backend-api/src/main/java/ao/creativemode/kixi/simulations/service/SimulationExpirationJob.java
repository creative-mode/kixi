package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.simulations.config.SimulationTimeLimitProperties;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationStatus;
import ao.creativemode.kixi.simulations.repository.SimulationRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import reactor.core.publisher.Mono;

/**
 * Issue #107: closes the simulations whose time ran out on the server.
 *
 * <p>The answer gate already refuses answers past the deadline; this sweep is
 * what makes the simulation itself finish — with whatever the student managed
 * to answer — so a paper left open does not sit in progress forever.</p>
 */
@Component
public class SimulationExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(SimulationExpirationJob.class);

    /**
     * Upper bound on one sweep. The scheduler only schedules the next sweep
     * after this one returns, so blocking without a timeout would let a stuck
     * driver postpone every later poll indefinitely.
     */
    private static final Duration SWEEP_TIMEOUT = Duration.ofSeconds(60);

    private final SimulationRepository simulations;
    private final SimulationDeadlineService deadlineService;
    private final SimulationSubmissionService submissionService;
    private final SimulationTimeLimitProperties properties;
    private final Clock clock;

    public SimulationExpirationJob(
            SimulationRepository simulations,
            SimulationDeadlineService deadlineService,
            SimulationSubmissionService submissionService,
            SimulationTimeLimitProperties properties) {
        this(simulations, deadlineService, submissionService, properties, Clock.systemDefaultZone());
    }

    @Autowired
    public SimulationExpirationJob(
            SimulationRepository simulations,
            SimulationDeadlineService deadlineService,
            SimulationSubmissionService submissionService,
            SimulationTimeLimitProperties properties,
            Clock clock) {
        this.simulations = simulations;
        this.deadlineService = deadlineService;
        this.submissionService = submissionService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Blocking here is safe: a {@code @Scheduled} method runs on the
     * scheduler's own thread, never on the WebFlux event loop, and the
     * blocking is bounded by {@link #SWEEP_TIMEOUT}.
     */
    @Scheduled(fixedDelayString = "${app.simulations.expiry-poll-ms:60000}")
    public void closeExpiredSimulations() {
        if (!properties.isExpiryEnabled()) {
            return;
        }
        // One reading of the server clock for the whole sweep, so a long run
        // cannot close a simulation that expired halfway through it.
        LocalDateTime now = LocalDateTime.now(clock);
        simulations.findByStatusAndDeletedAtIsNull(SimulationStatus.IN_PROGRESS)
                .concatMap(simulation -> closeIfExpired(simulation, now))
                .then()
                .doOnError(error -> log.error("Simulation expiration sweep failed", error))
                .onErrorResume(error -> Mono.empty())
                .block(SWEEP_TIMEOUT);
    }

    /**
     * One simulation failing — a paper submitted by the student in the same
     * instant, a database hiccup — must not hold back the ones after it, so
     * the failure is swallowed here rather than on the sweep.
     */
    private Mono<Void> closeIfExpired(Simulation simulation, LocalDateTime now) {
        return deadlineService.expired(simulation, now)
                .filter(Boolean.TRUE::equals)
                .flatMap(expired -> close(simulation))
                .onErrorResume(error -> {
                    log.warn("Could not close expired simulation {}", simulation.getId(), error);
                    return Mono.empty();
                });
    }

    private Mono<Void> close(Simulation simulation) {
        return submissionService.submit(simulation.getId(), null, true)
                .doOnNext(closed -> log.info("Closed expired simulation {}", closed.getId()))
                .then();
    }
}
