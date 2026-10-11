package ao.creativemode.kixi.analytics;

import ao.creativemode.kixi.analytics.dto.AnalyticsDtos.Scope;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.shared.service.TeachingScopeReader;

import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Who is asking, and what that lets them see.
 *
 * <p>An administrator sees the whole platform. A teacher sees the classes they are
 * assigned to teach, and the statements of those classes even when a colleague wrote
 * them: the dashboard asks for delivery times and learning gaps, which are facts about
 * their students rather than about their own authorship.</p>
 *
 * <p>Carried as data rather than as a boolean, so the repository receives one predicate
 * it can put in SQL instead of fetching everything and filtering afterwards.</p>
 */
@Component
public class DashboardScopeResolver {

    /** Matches no class, so a teacher without an assignment sees nothing, not all. */
    private static final long NO_CLASS = -1L;

    private final TeachingScopeReader teaching;
    private final CurrentAccountService accounts;

    public DashboardScopeResolver(TeachingScopeReader teaching, CurrentAccountService accounts) {
        this.teaching = teaching;
        this.accounts = accounts;
    }

    /**
     * The scope for this caller, with the class ids it covers.
     *
     * <p>An administrator has no class filter at all: {@code null} means every class, not
     * every class the reader happened to know about.</p>
     */
    public Mono<DashboardScope> resolve(Long accountId) {
        return accounts.hasAnyRole("ADMIN")
                .flatMap(admin -> Boolean.TRUE.equals(admin)
                        ? Mono.just(new DashboardScope(Scope.ADMIN, null))
                        : teaching.classIdsTaughtBy(accountId)
                                .collectList()
                                .map(classIds -> new DashboardScope(
                                        Scope.TEACHER,
                                        classIds.isEmpty() ? List.of(NO_CLASS) : classIds)));
    }

    /** The audience of the request: who it is, and which classes it may see. */
    public record DashboardScope(Scope scope, List<Long> classIds) {

        /** True when this scope is not narrowed to any class. */
        public boolean everything() {
            return classIds == null;
        }
    }
}