package ao.creativemode.kixi.simulations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.simulations.config.SimulationTimeLimitProperties;
import ao.creativemode.kixi.simulations.model.Simulation;
import ao.creativemode.kixi.simulations.model.SimulationStatus;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Issue #107 (BE-11): the server owns the time of the simulation. These
 * tests pin the deadline arithmetic on an explicit clock — the caller passes
 * "now" in — so nothing here depends on when the suite happens to run.
 */
class SimulationDeadlineServiceTest {

    private static final long STATEMENT_ID = 9L;
    private static final long ACCOUNT_ID = 42L;

    private StatementRepository statements;
    private AccountRepository accounts;
    private SimulationDeadlineService service;

    private final LocalDateTime now = LocalDateTime.of(2026, 3, 1, 10, 0, 0);

    @BeforeEach
    void setUp() {
        statements = mock(StatementRepository.class);
        accounts = mock(AccountRepository.class);
        service = new SimulationDeadlineService(
                statements, accounts, new SimulationTimeLimitProperties());
    }

    @Test
    void statementWithoutDurationHasNoDeadline() {
        Simulation simulation = started(now.minusHours(1));
        givenStatement(null);

        StepVerifier.create(service.deadline(simulation)).verifyComplete();
        StepVerifier.create(service.expired(simulation, now)).expectNext(false).verifyComplete();
    }

    @Test
    void simulationThatNeverStartedHasNoDeadline() {
        Simulation simulation = new Simulation();
        simulation.setStatementId(STATEMENT_ID);
        simulation.setAccountId(ACCOUNT_ID);
        when(statements.findById(STATEMENT_ID)).thenReturn(Mono.just(statement(30)));

        StepVerifier.create(service.deadline(simulation)).verifyComplete();
        StepVerifier.create(service.expired(simulation, now)).expectNext(false).verifyComplete();
    }

    @Test
    void simulationWithoutStatementHasNoDeadline() {
        Simulation simulation = started(now.minusHours(5));
        simulation.setStatementId(null);

        StepVerifier.create(service.deadline(simulation)).verifyComplete();
        StepVerifier.create(service.expired(simulation, now)).expectNext(false).verifyComplete();
    }

    @Test
    void missingStatementHasNoDeadline() {
        Simulation simulation = started(now.minusHours(5));
        when(statements.findById(STATEMENT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.deadline(simulation)).verifyComplete();
        StepVerifier.create(service.expired(simulation, now)).expectNext(false).verifyComplete();
    }

    @Test
    void deadlineIsTheEffectiveClosingInstant() {
        Simulation simulation = started(now.minusMinutes(29));
        givenStatement(30);
        givenAccount(null);

        StepVerifier.create(service.deadline(simulation))
                .expectNext(now.minusMinutes(29).plusMinutes(30).plusSeconds(10))
                .verifyComplete();
    }

    @Test
    void simulationPastItsDurationIsExpired() {
        Simulation simulation = started(now.minusMinutes(40));
        givenStatement(30);
        givenAccount(null);

        StepVerifier.create(service.expired(simulation, now)).expectNext(true).verifyComplete();
    }

    @Test
    void simulationInsideItsDurationIsNotExpired() {
        Simulation simulation = started(now.minusMinutes(29));
        givenStatement(30);
        givenAccount(null);

        StepVerifier.create(service.expired(simulation, now)).expectNext(false).verifyComplete();
    }

    @Test
    void toleranceKeepsTheSimulationOpenRightAfterTheDeadline() {
        Simulation simulation = started(now.minusSeconds(30 * 60L + 5));
        givenStatement(30);
        givenAccount(null);

        StepVerifier.create(service.expired(simulation, now)).expectNext(false).verifyComplete();
    }

    @Test
    void simulationExpiresOnceTheToleranceIsUsedUp() {
        Simulation simulation = started(now.minusSeconds(30 * 60L + 11));
        givenStatement(30);
        givenAccount(null);

        StepVerifier.create(service.expired(simulation, now)).expectNext(true).verifyComplete();
    }

    @Test
    void acceptsAnswersIsTheOppositeOfExpired() {
        Simulation simulation = started(now.minusMinutes(40));
        givenStatement(30);
        givenAccount(null);

        StepVerifier.create(service.acceptsAnswers(simulation, now)).expectNext(false).verifyComplete();
    }

    /**
     * The accessibility extra time of issue #107 is +25% of the duration, with
     * no rounding: a 30 minute paper runs for 37.5 minutes, not 38.
     */
    @Test
    void accessibilityExtraTimeExtendsTheDeadlineByTwentyFivePercent() {
        Simulation simulation = started(now.minusMinutes(35));
        givenStatement(30);
        givenAccount(Boolean.TRUE);

        StepVerifier.create(service.deadline(simulation))
                .expectNext(now.minusMinutes(35).plusMinutes(30).plusSeconds(30 * 60L / 4 + 10))
                .verifyComplete();
        StepVerifier.create(service.expired(simulation, now)).expectNext(false).verifyComplete();
    }

    @Test
    void simulationPastTheExtraTimeIsExpired() {
        Simulation simulation = started(now.minusMinutes(48));
        givenStatement(30);
        givenAccount(Boolean.TRUE);

        StepVerifier.create(service.expired(simulation, now)).expectNext(true).verifyComplete();
    }

    @Test
    void accountWithoutTheFlagGetsThePlainDurationPlusTolerance() {
        Simulation simulation = started(now.minusMinutes(35));
        givenStatement(30);
        givenAccount(Boolean.FALSE);

        StepVerifier.create(service.deadline(simulation))
                .expectNext(now.minusMinutes(35).plusMinutes(30).plusSeconds(10))
                .verifyComplete();
    }

    @Test
    void missingAccountGetsThePlainDurationPlusTolerance() {
        Simulation simulation = started(now.minusMinutes(35));
        givenStatement(30);
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.deadline(simulation))
                .expectNext(now.minusMinutes(35).plusMinutes(30).plusSeconds(10))
                .verifyComplete();
    }

    @Test
    void accountIsNotReadWhenTheStatementHasNoDuration() {
        Simulation simulation = started(now.minusHours(3));
        givenStatement(null);

        StepVerifier.create(service.deadline(simulation)).verifyComplete();

        org.mockito.Mockito.verifyNoInteractions(accounts);
    }

    @Test
    void effectiveDeadlineForIsThePureArithmeticBehindTheDeadline() {
        LocalDateTime start = LocalDateTime.of(2026, 3, 1, 9, 0, 0);

        assertThat(service.effectiveDeadlineFor(start, 30, false))
                .isEqualTo(start.plusMinutes(30).plusSeconds(10));
        assertThat(service.effectiveDeadlineFor(start, 30, true))
                .isEqualTo(start.plusSeconds(30 * 75 + 10));
        assertThat(service.effectiveDeadlineFor(null, 30, false)).isNull();
        assertThat(service.effectiveDeadlineFor(start, null, true)).isNull();
    }

    private void givenStatement(Integer durationMinutes) {
        when(statements.findById(STATEMENT_ID)).thenReturn(Mono.just(statement(durationMinutes)));
    }

    private void givenAccount(Boolean accessibilityExtraTime) {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setAccessibilityExtraTime(accessibilityExtraTime);
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Mono.just(account));
    }

    private Statement statement(Integer durationMinutes) {
        Statement statement = new Statement();
        statement.setId(STATEMENT_ID);
        statement.setDurationMinutes(durationMinutes);
        return statement;
    }

    private Simulation started(LocalDateTime startedAt) {
        Simulation simulation = new Simulation();
        simulation.setId(1L);
        simulation.setAccountId(ACCOUNT_ID);
        simulation.setStatementId(STATEMENT_ID);
        simulation.setStartedAt(startedAt);
        simulation.setStatus(SimulationStatus.IN_PROGRESS);
        return simulation;
    }
}
