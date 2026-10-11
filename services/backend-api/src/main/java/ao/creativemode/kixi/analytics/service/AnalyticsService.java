package ao.creativemode.kixi.analytics.service;

import ao.creativemode.kixi.analytics.DashboardScopeResolver;
import ao.creativemode.kixi.analytics.DashboardScopeResolver.DashboardScope;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.ActivityPoint;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.ClassStats;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.InstitutionStats;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.Overview;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.OverviewSimulations;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.OverviewStatements;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.QuestionStats;
import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.StatementStats;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.ClassRow;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.InstitutionRow;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.PopulationCounts;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.QuestionRow;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.ScoreSummary;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.SimulationCounts;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.StatementCounts;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.StatementRow;
import ao.creativemode.kixi.shared.exception.ApiException;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * The dashboard of issue #110.
 *
 * <p>Two invariants run through everything here:</p>
 * <ul>
 *   <li><b>No data is null, never zero.</b> A school where nobody sat the paper has no
 *       average. Reporting 0 there says the whole school failed.</li>
 *   <li><b>The scope comes from the caller, never from a parameter.</b> There is no
 *       request shape that reaches a class the caller does not teach.</li>
 * </ul>
 *
 * <p>Averages are percentages of the statement's own maximum, never raw scores: the same
 * dashboard would otherwise be adding 12.5 out of 20 to 80 out of 100.</p>
 */
@Service
public class AnalyticsService {

    /** The dashboard asks for at most a year of activity, and at least a day. */
    static final int MAX_DAYS = 365;
    static final int MAX_STATEMENTS = 200;
    private static final int DEFAULT_LIMIT = 50;

    private final DashboardScopeResolver scopes;
    private final AnalyticsRepository repository;
    private final Clock clock;

    public AnalyticsService(DashboardScopeResolver scopes, AnalyticsRepository repository) {
        this(scopes, repository, Clock.systemUTC());
    }

    /** The clock is injected so the activity window can be tested without waiting. */
    public AnalyticsService(
            DashboardScopeResolver scopes, AnalyticsRepository repository, Clock clock) {
        this.scopes = scopes;
        this.repository = repository;
        this.clock = clock;
    }

    // ── /overview ───────────────────────────────────────────────────────────

    public Mono<Overview> overview(Long accountId) {
        return scopes.resolve(accountId).flatMap(scope -> Mono.zip(
                        // The four aggregates are independent, so they go out together
                        // rather than one after the other.
                        repository.statementCounts(scope.classIds())
                                .next().defaultIfEmpty(EMPTY_STATEMENTS),
                        repository.simulationCounts(scope.classIds())
                                .next().defaultIfEmpty(EMPTY_SIMULATIONS),
                        repository.populationCounts(scope.classIds())
                                .next().defaultIfEmpty(EMPTY_POPULATION),
                        repository.scoreSummary(scope.classIds())
                                .defaultIfEmpty(EMPTY_SCORE))
                .map(tuple -> {
                    StatementCounts statements = tuple.getT1();
                    SimulationCounts simulations = tuple.getT2();
                    PopulationCounts population = tuple.getT3();
                    ScoreSummary score = tuple.getT4();
                    return new Overview(
                            scope.scope(),
                            population.institutions(),
                            population.classes(),
                            population.students(),
                            population.teachers(),
                            new OverviewStatements(
                                    statements.total(), statements.published(),
                                    statements.needingReview(), statements.missingAnswerKey()),
                            new OverviewSimulations(
                                    simulations.total(), simulations.finished(),
                                    simulations.inProgress(), simulations.cancelled(),
                                    simulations.uniqueStudents()),
                            score.averageScore(),
                            score.passRate(),
                            score.averageTime());
                }));
    }

    // ── /activity ───────────────────────────────────────────────────────────

    public Flux<ActivityPoint> activity(Long accountId, Integer days) {
        int window = Math.min(Math.max(days == null ? 30 : days, 1), MAX_DAYS);
        LocalDateTime now = LocalDateTime.now(clock);
        return scopes.resolve(accountId)
                .flatMapMany(scope -> repository.activity(
                        scope.classIds(), now.minusDays(window), now))
                .map(point -> new ActivityPoint(point.date(), point.finished(), point.averageScore()));
    }

    // ── /institutions and /classes ──────────────────────────────────────────

    public Flux<InstitutionStats> institutions(Long accountId) {
        return scopes.resolve(accountId)
                .flatMapMany(scope -> repository.institutionStats(scope.classIds()))
                .map(AnalyticsService::toInstitutionStats);
    }

    public Flux<ClassStats> classes(Long accountId) {
        return scopes.resolve(accountId)
                .flatMapMany(scope -> repository.classStats(scope.classIds()))
                .map(AnalyticsService::toClassStats);
    }

    // ── /statements ─────────────────────────────────────────────────────────

    public Flux<StatementStats> statements(Long accountId, Integer limit) {
        int capped = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_STATEMENTS);
        return scopes.resolve(accountId)
                .flatMapMany(scope ->
                        repository.statementStats(scope.classIds(), capped))
                .map(AnalyticsService::toStatementStats);
    }

    /**
     * Per-question stats for one statement, and only for a statement the caller may see.
     *
     * <p>The existence check is a real check and not a formality: without it the 404 would
     * be the only thing telling a teacher they cannot look at a paper from another school,
     * which is a different answer from "no such paper".</p>
     */
    public Flux<QuestionStats> questions(Long accountId, Long statementId) {
        return scopes.resolve(accountId)
                .flatMapMany(scope -> readable(scope, statementId)
                        .flatMapMany(ignored -> repository.questionStats(statementId)))
                .map(AnalyticsService::toQuestionStats);
    }

    private Mono<Boolean> readable(DashboardScope scope, Long statementId) {
        return repository.statementReadable(scope.classIds(), statementId)
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(ApiException.notFound("Statement not found")));
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private static InstitutionStats toInstitutionStats(InstitutionRow row) {
        return new InstitutionStats(
                row.id(), row.code(), row.name(), row.courses(), row.classes(),
                row.students(), row.finishedSimulations(), row.averageScore(), row.averageTime());
    }

    private static ClassStats toClassStats(ClassRow row) {
        return new ClassStats(
                row.id(), row.code(), row.grade(), row.course(), row.institution(),
                row.students(), row.teachers(), row.statements(), row.finishedSimulations(),
                row.averageScore(), row.averageTime());
    }

    private static StatementStats toStatementStats(StatementRow row) {
        return new StatementStats(
                row.id(), row.title(), row.examType(), row.classCode(), row.subject(),
                row.published(), row.needsReview(), row.answerKeyComplete(), row.questions(),
                row.finishedSimulations(), row.uniqueStudents(),
                row.averageScore(), row.passRate(), row.averageTime());
    }

    private static QuestionStats toQuestionStats(QuestionRow row) {
        return new QuestionStats(
                row.id(), row.number(), row.text(), row.questionType(), row.maxScore(),
                row.attempts(), row.answered(), row.correct(), row.correctPercent());
    }

    private static final StatementCounts EMPTY_STATEMENTS =
            new StatementCounts(0, 0, 0, 0);
    private static final SimulationCounts EMPTY_SIMULATIONS =
            new SimulationCounts(0, 0, 0, 0, 0);
    private static final PopulationCounts EMPTY_POPULATION =
            new PopulationCounts(0, 0, 0, 0);
    private static final ScoreSummary EMPTY_SCORE =
            new ScoreSummary(null, null, null);
}