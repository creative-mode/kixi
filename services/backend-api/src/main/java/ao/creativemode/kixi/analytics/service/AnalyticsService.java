package ao.creativemode.kixi.analytics.service;

import ao.creativemode.kixi.analytics.dto.ActivityPoint;
import ao.creativemode.kixi.analytics.dto.ClassStats;
import ao.creativemode.kixi.analytics.dto.ClassStudentStats;
import ao.creativemode.kixi.analytics.dto.HardQuestion;
import ao.creativemode.kixi.analytics.dto.InstitutionStats;
import ao.creativemode.kixi.analytics.dto.Overview;
import ao.creativemode.kixi.analytics.dto.QuestionStats;
import ao.creativemode.kixi.analytics.dto.StatementStats;
import ao.creativemode.kixi.analytics.dto.WeeklyPoint;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository;
import ao.creativemode.kixi.analytics.repository.AnalyticsRepository.Access;
import ao.creativemode.kixi.analytics.repository.AnalyticsScope;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The dashboard numbers, narrowed to what the caller may see (BE-14, BE-15).
 *
 * <p>The scope comes from the authenticated account, never from a parameter: a
 * teacher cannot ask for another teacher's classes by naming them. Asking for a
 * class or a statement outside the scope answers 403, not an empty list, so the
 * dashboard can tell "nothing yet" from "not yours".</p>
 */
@Service
public class AnalyticsService {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 365;
    static final int DEFAULT_STATEMENTS = 50;
    static final int MAX_STATEMENTS = 200;
    static final int DEFAULT_WEEKS = 8;
    static final int MAX_WEEKS = 52;
    static final int DEFAULT_QUESTIONS = 10;
    static final int MAX_QUESTIONS = 50;

    private final AnalyticsRepository repository;
    private final CurrentAccountService current;

    public AnalyticsService(AnalyticsRepository repository, CurrentAccountService current) {
        this.repository = repository;
        this.current = current;
    }

    public Mono<Overview> overview() {
        return scope().flatMap(repository::overview);
    }

    public Flux<ActivityPoint> activity(Integer days) {
        int n = within("days", days, DEFAULT_DAYS, MAX_DAYS);
        return scope().flatMapMany(scope -> repository.activity(scope, n));
    }

    public Flux<InstitutionStats> institutions() {
        return scope().flatMapMany(repository::institutions);
    }

    public Flux<ClassStats> classes() {
        return scope().flatMapMany(repository::classes);
    }

    public Flux<StatementStats> statements(Integer limit) {
        int n = within("limit", limit, DEFAULT_STATEMENTS, MAX_STATEMENTS);
        return scope().flatMapMany(scope -> repository.statements(scope, n));
    }

    public Flux<QuestionStats> statementQuestions(long statementId) {
        return scope().flatMapMany(scope -> repository.statementAccess(scope, statementId)
                .flatMapMany(access -> require(access, "Statement")
                        .thenMany(repository.statementQuestions(scope, statementId))));
    }

    public Flux<ClassStudentStats> classStudents(long classId) {
        return inClass(classId, scope -> repository.classStudents(scope, classId));
    }

    public Flux<WeeklyPoint> classWeekly(long classId, Integer weeks) {
        int n = within("weeks", weeks, DEFAULT_WEEKS, MAX_WEEKS);
        return inClass(classId, scope -> repository.classWeekly(scope, classId, n));
    }

    public Flux<HardQuestion> classHardestQuestions(long classId, Integer limit) {
        int n = within("limit", limit, DEFAULT_QUESTIONS, MAX_QUESTIONS);
        return inClass(classId, scope -> repository.classHardestQuestions(scope, classId, n));
    }

    private <T> Flux<T> inClass(long classId, java.util.function.Function<AnalyticsScope, Flux<T>> query) {
        return scope().flatMapMany(scope -> repository.classAccess(scope, classId)
                .flatMapMany(access -> require(access, "Class").thenMany(query.apply(scope))));
    }

    private Mono<AnalyticsScope> scope() {
        return current.requiredAccountId()
                .zipWith(current.hasAnyRole("ADMIN"))
                .map(tuple -> new AnalyticsScope(tuple.getT1(), tuple.getT2()));
    }

    private static Mono<Void> require(Access access, String what) {
        return switch (access) {
            case ALLOWED -> Mono.empty();
            case FORBIDDEN -> Mono.error(ApiException.forbidden(what + " is outside your teaching assignments"));
            case NOT_FOUND -> Mono.error(ApiException.notFound(what + " not found"));
        };
    }

    /** The value, or the default when absent; 400 outside 1..max. */
    static int within(String name, Integer value, int fallback, int max) {
        if (value == null) return fallback;
        if (value < 1 || value > max) {
            throw ApiException.badRequest(name + " must be between 1 and " + max);
        }
        return value;
    }
}
