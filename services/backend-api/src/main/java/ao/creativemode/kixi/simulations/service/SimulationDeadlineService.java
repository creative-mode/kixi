package ao.creativemode.kixi.simulations.service;

import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.simulations.config.SimulationTimeLimitProperties;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.shared.service.ExamRoomAccess;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import reactor.core.publisher.Mono;

/**
 * The single source of truth for the time of a simulation (issue #107).
 *
 * <p>The effective deadline is the moment the server stops accepting answers:
 * statement duration, accessibility extra time, and configured tolerance are
 * all included. It is computed here and nowhere else so the answer gate, the
 * expiration job, and the response cannot disagree on it.</p>
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
    private final ExamRoomAccess examRoomAccess;

    @Autowired
    public SimulationDeadlineService(
            StatementRepository statements,
            AccountRepository accounts,
            SimulationTimeLimitProperties properties,
            ExamRoomAccess examRoomAccess) {
        this.statements = statements;
        this.accounts = accounts;
        this.properties = properties;
        this.examRoomAccess = examRoomAccess;
    }

    public SimulationDeadlineService(StatementRepository statements, AccountRepository accounts,
            SimulationTimeLimitProperties properties) {
        this(statements, accounts, properties, null);
    }

    /**
     * The effective instant after which the simulation no longer accepts
     * answers, or empty when it has no deadline.
     */
    public Mono<LocalDateTime> deadline(Simulation simulation) {
        LocalDateTime startedAt = simulation.getStartedAt();
        Long statementId = simulation.getStatementId();
        if (startedAt == null || statementId == null) {
            return Mono.empty();
        }
        if (simulation.getExamRoomDurationMinutes() != null) {
            return hasExtraTime(simulation.getAccountId())
                    .flatMap(extraTime -> withRoomEnd(simulation,
                            effectiveDeadlineFor(startedAt, simulation.getExamRoomDurationMinutes(), extraTime)));
        }
        return statements.findById(statementId)
                // No duration means no deadline to extend, so the account is not read.
                .filter(statement -> statement.getDurationMinutes() != null)
                .flatMap(statement -> hasExtraTime(simulation.getAccountId())
                        .flatMap(extraTime -> Mono.justOrEmpty(effectiveDeadlineFor(
                                startedAt, statement.getDurationMinutes(), extraTime))));
    }

    /**
     * Whether the server clock has already passed the effective deadline. A
     * simulation without a deadline never expires.
     *
     * @param now the server clock, passed in so the caller decides when it is read
     */
    public Mono<Boolean> expired(Simulation simulation, LocalDateTime now) {
        if (simulation.getStartedAt() != null && now.isBefore(simulation.getStartedAt())) return Mono.just(true);
        return deadline(simulation).map(deadline -> !now.isBefore(deadline)).defaultIfEmpty(false);
    }

    /**
     * Whether the simulation still accepts answers at the given instant.
     */
    public Mono<Boolean> acceptsAnswers(Simulation simulation, LocalDateTime now) {
        return expired(simulation, now).map(expired -> !expired);
    }

    private Mono<LocalDateTime> withRoomEnd(Simulation simulation, LocalDateTime deadline) {
        if (examRoomAccess == null || simulation.getExamRoomId() == null) return Mono.justOrEmpty(deadline);
        return examRoomAccess.effectiveRoomDeadline(simulation.getExamRoomId(), deadline);
    }

    /**
     * The pure effective-deadline arithmetic, so a caller that already holds
     * the statement and the account does not have to query them again.
     *
     * @return the deadline, or null when there is nothing to count from
     */
    public LocalDateTime effectiveDeadlineFor(LocalDateTime startedAt, Integer durationMinutes,
            boolean extraTime) {
        if (startedAt == null || durationMinutes == null) {
            return null;
        }
        long secondsPerMinute = extraTime
                ? EXTRA_TIME_SECONDS_PER_MINUTE
                : SECONDS_PER_MINUTE;
        return startedAt.plusSeconds(durationMinutes * secondsPerMinute
                + properties.getToleranceSeconds());
    }

    private Mono<Boolean> hasExtraTime(Long accountId) {
        return Mono.justOrEmpty(accountId)
                .flatMap(accounts::findById)
                .map(account -> Boolean.TRUE.equals(account.getAccessibilityExtraTime()))
                .defaultIfEmpty(false);
    }
}
