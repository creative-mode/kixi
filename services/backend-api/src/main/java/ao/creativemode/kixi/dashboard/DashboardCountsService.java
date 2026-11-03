package ao.creativemode.kixi.dashboard;

import ao.creativemode.kixi.exams.service.StatementReadAccessService;
import ao.creativemode.kixi.exams.service.StatementService;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.simulations.service.SimulationAnswerService;
import ao.creativemode.kixi.simulations.service.SimulationService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * How many active records of each kind the caller would see in the manager's lists, in one call (issue #416), so the
 * dashboard stops downloading 17 lists to read their {@code length}.
 *
 * <p>The numbers are the lists' own, per role. The ADMIN lists everything active, so every count is a {@code COUNT(*)}.
 * A TEACHER may not read six of those lists ({@code SecurityConfig} answers 403), so their keys are left out; the
 * reference data and their own enrollments are a {@code COUNT(*)} too. Statements, the review queue, simulations and
 * answers are narrowed per row by rules that live in their services ({@code StatementReadScope.allows},
 * {@code SimulationService.authorize}); those four are counted through the very methods the lists use, on the server,
 * so a teacher's count cannot drift from the list a teacher gets. Rewriting those rules in SQL would be the way to
 * make it drift.</p>
 */
@Service
public class DashboardCountsService {

    private final DashboardCountsRepository repository;
    private final CurrentAccountService current;
    private final StatementService statements;
    private final StatementReadAccessService statementAccess;
    private final SimulationService simulations;
    private final SimulationAnswerService answers;

    public DashboardCountsService(DashboardCountsRepository repository, CurrentAccountService current,
            StatementService statements, StatementReadAccessService statementAccess, SimulationService simulations,
            SimulationAnswerService answers) {
        this.repository = repository;
        this.current = current;
        this.statements = statements;
        this.statementAccess = statementAccess;
        this.simulations = simulations;
        this.answers = answers;
    }

    public Mono<Map<String, Long>> counts() {
        return Mono.zip(current.requiredAccountId(), current.hasAnyRole("ADMIN"), current.hasAnyRole("TEACHER"))
                .flatMap(caller -> countsFor(caller.getT1(), caller.getT2(), caller.getT3()));
    }

    /** The counts of an account with these roles. Public for the tests that compare them with the lists. */
    public Mono<Map<String, Long>> countsFor(long accountId, boolean admin, boolean teacher) {
        if (admin) return repository.all();
        if (!teacher) return Mono.error(ApiException.forbidden("Only ADMIN or TEACHER can read the dashboard counts"));
        return teacherCounts(accountId);
    }

    private Mono<Map<String, Long>> teacherCounts(long accountId) {
        Mono<Long> statementCount = statementAccess.teacherScope(accountId)
                .flatMap(scope -> statements.findAllActive().filter(scope::allows).count());
        Mono<Long> reviewCount = statementAccess.teacherScope(accountId)
                .flatMap(scope -> statements.findNeedingReview().filter(scope::allows).count());
        // The total of a one-item page: authorisation runs on every simulation, but only one becomes a response.
        Mono<Long> simulationCount = simulations.findAllAuthorized(accountId, false, true, null, null, 0, 1)
                .map(SimulationService.SimulationPage::total);
        Mono<Long> answerCount = answers.findAllActiveForStaff(accountId, false).count();

        return Mono.zip(repository.reference(), repository.ownEnrollments(accountId), statementCount, reviewCount,
                        simulationCount, answerCount)
                .map(parts -> {
                    Map<String, Long> scoped = new LinkedHashMap<>(parts.getT1());
                    scoped.put("enrollments", parts.getT2());
                    scoped.put("statements", parts.getT3());
                    scoped.put(DashboardCountsRepository.REVIEW, parts.getT4());
                    scoped.put("simulations", parts.getT5());
                    scoped.put("simulation-answers", parts.getT6());
                    return inDashboardOrder(scoped);
                });
    }

    /** The keys in the order of {@link DashboardCountsRepository#TABLES}, the review queue last, as for the ADMIN. */
    private static Map<String, Long> inDashboardOrder(Map<String, Long> counts) {
        Map<String, Long> ordered = new LinkedHashMap<>();
        DashboardCountsRepository.TABLES.keySet().forEach(key -> {
            if (counts.containsKey(key)) ordered.put(key, counts.get(key));
        });
        if (counts.containsKey(DashboardCountsRepository.REVIEW)) {
            ordered.put(DashboardCountsRepository.REVIEW, counts.get(DashboardCountsRepository.REVIEW));
        }
        return ordered;
    }
}
