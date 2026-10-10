package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.simulations.config.SimulationTimeLimitProperties;
import ao.creativemode.kixi.simulations.model.Simulation;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/**
 * The single source of truth for the time of a simulation (issue #107).
 *
 * <p>The deadline is the moment the statement's duration, counted from the
 * instant the server started the simulation, is up. An account flagged for
 * accessibility gets +25% of that duration, computed here and nowhere else so
 * the answer gate and the expiration job can never disagree on it.</p>
 *
 * <p>A simulation without a statement, without a start or whose statement has
 * no duration has no deadline at all: it never expires, which is what the
 * caller sees as an empty {@link Mono}.</p>
 */
@Service
public class SimulationDeadlineService {

    private static final long SECONDS_PER_MINUTE = 60;
    /** +25% on the duration, in whole seconds per minute (1.25 * 60). */
    private static final long EXTRA_TIME_SECONDS_PER_MINUTE = 75;

    private final StatementRepository statements;
    private final AccountRepository accounts;
    private final SimulationTimeLimitProperties properties;

    public SimulationDeadlineService(
            StatementRepository statements,
            AccountRepository accounts,
            SimulationTimeLimitProperties properties) {
        this.statements = statements;
        this.accounts = accounts;
        this.properties = properties;
    }

    /**
     * The instant the simulation is due, or empty when it has no deadline.
     */
    public Mono<LocalDateTime> deadline(Simulation simulation) {
        LocalDateTime startedAt = simulation.getStartedAt();
        Long statementId = simulation.getStatementId();
        if (startedAt == null || statementId == null) {
            return Mono.empty();
        }
        return statements.findById(statementId)
                // No duration means no deadline to extend, so the account is not read.
                .filter(statement -> statement.getDurationMinutes() != null)
                .flatMap(statement -> hasExtraTime(simulation.getAccountId())
                        .flatMap(extraTime -> Mono.justOrEmpty(deadlineFor(
                                startedAt, statement.getDurationMinutes(), extraTime))));
    }

    /**
     * Whether the server clock has already passed the deadline plus the
     * tolerance. A simulation without a deadline never expires.
     *
     * @param now the server clock, passed in so the caller decides when it is read
     */
    public Mono<Boolean> expired(Simulation simulation, LocalDateTime now) {
        return deadline(simulation)
                .map(deadline -> now.isAfter(
                        deadline.plusSeconds(properties.getToleranceSeconds())))
                .defaultIfEmpty(false);
    }

    /**
     * Whether the simulation still accepts answers at the given instant.
     */
    public Mono<Boolean> acceptsAnswers(Simulation simulation, LocalDateTime now) {
        return expired(simulation, now).map(expired -> !expired);
    }

    /**
     * The pure deadline arithmetic, so a caller that already holds the statement
     * and the account does not have to query them again.
     *
     * @return the deadline, or null when there is nothing to count from
     */
    public LocalDateTime deadlineFor(LocalDateTime startedAt, Integer durationMinutes,
            boolean extraTime) {
        if (startedAt == null || durationMinutes == null) {
            return null;
        }
        long secondsPerMinute = extraTime
                ? EXTRA_TIME_SECONDS_PER_MINUTE
                : SECONDS_PER_MINUTE;
        return startedAt.plusSeconds(durationMinutes * secondsPerMinute);
    }

    private Mono<Boolean> hasExtraTime(Long accountId) {
        return Mono.justOrEmpty(accountId)
                .flatMap(accounts::findById)
                .map(account -> Boolean.TRUE.equals(account.getAccessibilityExtraTime()))
                .defaultIfEmpty(false);
    }
}