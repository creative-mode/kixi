package ao.creativemode.kixi.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.analytics.DashboardScopeResolver;
import ao.creativemode.kixi.analytics.DashboardScopeResolver.DashboardScope;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.ClassStats;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.Overview;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.QuestionStats;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.StatementStats;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.ClassRow;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.DayPoint;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.PopulationCounts;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.QuestionRow;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.ScoreSummary;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.SimulationCounts;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.StatementCounts;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.StatementRow;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.Scope;
import ao.creativemode.kixi.shared.exception.ApiException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AnalyticsServiceTest {

    private static final Long ADMIN = 1L;
    private static final Long TEACHER = 2L;
    private static final Long CLASS_ID = 7L;
    private static final Long OTHER_CLASS_ID = 9L;

    private DashboardScopeResolver scopes;
    private AnalyticsRepository repository;
    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        scopes = mock(DashboardScopeResolver.class);
        repository = mock(AnalyticsRepository.class);
        service = new AnalyticsService(scopes, repository,
                Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC));
    }

    private void seesEverything() {
        when(scopes.resolve(anyLong())).thenReturn(Mono.just(new DashboardScope(Scope.ADMIN, null)));
    }

    private void teaches(Long... classIds) {
        when(scopes.resolve(anyLong()))
                .thenReturn(Mono.just(new DashboardScope(Scope.TEACHER, List.of(classIds))));
    }

    private void nothingSeeded() {
        when(repository.statementCounts(nullable(List.class))).thenReturn(Flux.empty());
        when(repository.simulationCounts(nullable(List.class))).thenReturn(Flux.empty());
        when(repository.populationCounts(nullable(List.class))).thenReturn(Flux.empty());
        when(repository.scoreSummary(nullable(List.class))).thenReturn(Mono.empty());
    }

    // ── /overview ───────────────────────────────────────────────────────────

    @Test
    void anAdministratorSeesTheWholePlatform() {
        seesEverything();
        when(repository.statementCounts(any()))
                .thenReturn(Flux.just(new StatementCounts(10, 7, 2, 1)));
        when(repository.simulationCounts(any()))
                .thenReturn(Flux.just(new SimulationCounts(40, 30, 7, 3, 12)));
        when(repository.populationCounts(any()))
                .thenReturn(Flux.just(new PopulationCounts(2, 14, 120, 9)));
        when(repository.scoreSummary(any()))
                .thenReturn(Mono.just(new ScoreSummary(71.4, 78.9, 3240.0)));

        Overview overview = service.overview(ADMIN).block();

        assertThat(overview.scope()).isEqualTo(Scope.ADMIN);
        assertThat(overview.institutions()).isEqualTo(2);
        assertThat(overview.statements().missingAnswerKey()).isEqualTo(1);
        assertThat(overview.simulations().finished()).isEqualTo(30);
        assertThat(overview.averageScorePercent()).isEqualTo(71.4);
    }

    /** An empty platform is zeros for the counts and null for the averages. */
    @Test
    void anEmptyPlatformReportsNoAveragesRatherThanZero() {
        seesEverything();
        nothingSeeded();

        Overview overview = service.overview(ADMIN).block();

        assertThat(overview.statements().total()).isZero();
        assertThat(overview.simulations().total()).isZero();
        assertThat(overview.institutions()).isZero();
        // Zero here would say the whole school failed every paper.
        assertThat(overview.averageScorePercent()).isNull();
        assertThat(overview.passRatePercent()).isNull();
        assertThat(overview.averageTimeSeconds()).isNull();
    }

    /** The audience is the caller, so it can never arrive as a parameter. */
    @Test
    void aTeacherOnlyEverSeesTheClassesTheyTeach() {
        teaches(CLASS_ID);
        nothingSeeded();

        Overview overview = service.overview(TEACHER).block();

        assertThat(overview.scope()).isEqualTo(Scope.TEACHER);
        verify(repository).statementCounts(List.of(CLASS_ID));
        verify(repository).simulationCounts(List.of(CLASS_ID));
        verify(repository).populationCounts(List.of(CLASS_ID));
        verify(repository).scoreSummary(List.of(CLASS_ID));
    }

    @Test
    void anAdministratorIsNotNarrowedToAnyClassList() {
        seesEverything();
        nothingSeeded();

        service.overview(ADMIN).block();

        // null means every class, not the empty list that would mean none.
        verify(repository).statementCounts(null);
        verify(repository).populationCounts(null);
    }

    // ── /activity ───────────────────────────────────────────────────────────

    @Test
    void theActivityWindowIsClampedAndAsksTheDatabaseForIt() {
        seesEverything();
        when(repository.activity(nullable(List.class), any(), any())).thenReturn(Flux.empty());

        service.activity(ADMIN, 5000).collectList().block();
        verify(repository).activity(eq(null), any(LocalDateTime.class), any(LocalDateTime.class));

        service.activity(ADMIN, 0).collectList().block();
        service.activity(ADMIN, null).collectList().block();
        // Nothing above throws: the days parameter is the only one a caller controls.
    }

    @Test
    void activityKeepsTheDayAndItsAverageAsTheyAre() {
        seesEverything();
        when(repository.activity(nullable(List.class), any(), any())).thenReturn(Flux.just(
                new DayPoint("2026-10-09", 4, 68.3),
                new DayPoint("2026-10-10", 0, null)));

        var points = service.activity(ADMIN, 2).collectList().block();

        assertThat(points).hasSize(2);
        assertThat(points.get(0).date()).isEqualTo("2026-10-09");
        assertThat(points.get(0).averageScorePercent()).isEqualTo(68.3);
        // A day nobody sat is zero finished and no average — not zero percent.
        assertThat(points.get(1).averageScorePercent()).isNull();
    }

    // ── /classes and /statements ────────────────────────────────────────────

    @Test
    void theStatementLimitIsClampedAndNeverTakenRaw() {
        seesEverything();
        when(repository.statementStats(nullable(List.class), anyInt())).thenReturn(Flux.empty());

        service.statements(ADMIN, 10_000).collectList().block();
        verify(repository).statementStats(eq(null), eq(AnalyticsService.MAX_STATEMENTS));

        service.statements(ADMIN, -5).collectList().block();
        verify(repository).statementStats(eq(null), eq(1));
    }

    @Test
    void statementRowsCrossOverIntact() {
        seesEverything();
        when(repository.statementStats(nullable(List.class), anyInt())).thenReturn(Flux.just(new StatementRow(
                3L, "Teste", "P1", "10A", "Matemática", true, false, true,
                12, 30, 25, 74.2, 80.0, 3100.0)));

        StatementStats stats = service.statements(ADMIN, 50).blockFirst();

        assertThat(stats.id()).isEqualTo(3L);
        assertThat(stats.averageScorePercent()).isEqualTo(74.2);
        assertThat(stats.passRatePercent()).isEqualTo(80.0);
        assertThat(stats.answerKeyComplete()).isTrue();
    }

    @Test
    void classRowsCrossOverIntact() {
        teaches(CLASS_ID);
        when(repository.classStats(List.of(CLASS_ID))).thenReturn(Flux.just(new ClassRow(
                CLASS_ID, "10A", 10, "Informática", "ITEL", 31, 4, 6, 128, 76.4, 3010.0)));

        ClassStats stats = service.classes(TEACHER).blockFirst();

        assertThat(stats.code()).isEqualTo("10A");
        assertThat(stats.institution()).isEqualTo("ITEL");
        assertThat(stats.finishedSimulations()).isEqualTo(128);
    }

    // ── /statements/{id}/questions ──────────────────────────────────────────

    @Test
    void aQuestionWithNoAutoGradingHasNoPercentage() {
        seesEverything();
        when(repository.statementReadable(nullable(List.class), anyLong())).thenReturn(Mono.just(true));
        when(repository.questionStats(anyLong())).thenReturn(Flux.just(
                new QuestionRow(1L, 1, "Quanto é 2+2?", "SHORT_ANSWER", 2.0, 10, 9, 0, null),
                new QuestionRow(2L, 2, "Escolhe", "MULTIPLE_CHOICE", 1.0, 10, 10, 8, 80.0)));

        List<QuestionStats> stats = service.questions(ADMIN, 5L).collectList().block();

        assertThat(stats).hasSize(2);
        // Unanswered counted as wrong: 0 of 10, but nothing to report a percentage about.
        assertThat(stats.get(0).correctPercent()).isNull();
        assertThat(stats.get(0).answered()).isEqualTo(9);
        assertThat(stats.get(1).correctPercent()).isEqualTo(80.0);
    }

    @Test
    void aStatementOutsideTheScopeIsNotFoundRatherThanEmpty() {
        teaches(CLASS_ID);
        // readable() says no, which is the same answer as a paper that does not exist.
        when(repository.statementReadable(List.of(CLASS_ID), 99L)).thenReturn(Mono.just(false));

        StepVerifier.create(service.questions(TEACHER, 99L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verify();
    }

    @Test
    void theScopeIsCheckedBeforeAnyQuestionIsRead() {
        teaches(CLASS_ID);
        when(repository.statementReadable(List.of(CLASS_ID), 99L)).thenReturn(Mono.just(false));

        StepVerifier.create(service.questions(TEACHER, 99L)).expectError(ApiException.class).verify();

        verify(repository, org.mockito.Mockito.never()).questionStats(anyLong());
    }
}