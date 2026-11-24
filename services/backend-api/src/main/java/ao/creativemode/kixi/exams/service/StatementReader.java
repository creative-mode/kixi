package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * Who is reading a statement, and how much of the school they may see.
 *
 * <p>The single read rule of the statement routes: an administrator reads
 * everything, a student only what is published, and a teacher (#174) what their
 * teaching assignments cover ({@link StatementReadScope}). The routes nested
 * under a statement (questions, options, answer key) apply the very same rule,
 * so a teacher who is refused {@code GET /statements/{id}} cannot read the
 * paper, or its answer key, through them.
 *
 * @param staff whether the caller reads as staff (sees drafts, the trash and the answer key)
 * @param scope the teacher's scope; {@code null} for an administrator or a student
 * @param coordinatedInstitutions the institutions the reader coordinates: a coordinator reads the questions of the statements of
 *                                their institution even while the exam they belong to has not finished (S8), without being staff
 *                                (no drafts, no answer key)
 */
public record StatementReader(boolean staff, StatementReadScope scope, Set<Long> coordinatedInstitutions) {

    public StatementReader {
        coordinatedInstitutions = coordinatedInstitutions == null ? Set.of() : Set.copyOf(coordinatedInstitutions);
    }

    public StatementReader(boolean staff, StatementReadScope scope) {
        this(staff, scope, Set.of());
    }

    /** An administrator: staff with no scope to weigh a statement against. */
    public static final StatementReader ADMIN = new StatementReader(true, null);

    /** A student, or any reader that is not staff: published statements only, no answer key. */
    public static final StatementReader STUDENT = new StatementReader(false, null);

    /** Resolves the reader of the current request. */
    public static Mono<StatementReader> current(CurrentAccountService currentAccount, StatementReadAccessService readAccess) {
        return Mono.zip(currentAccount.hasAnyRole("ADMIN"), currentAccount.hasAnyRole("TEACHER"), currentAccount.hasAnyRole("COORDINATOR"))
            .flatMap(roles -> {
                if (roles.getT1()) return Mono.just(ADMIN);
                Mono<Set<Long>> institutions = roles.getT3()
                    ? currentAccount.requiredAccountId().flatMap(readAccess::coordinatedInstitutions)
                    : Mono.just(Set.<Long>of());
                if (!roles.getT2()) return institutions.map(coordinated -> new StatementReader(false, null, coordinated));
                return currentAccount.requiredAccountId()
                    .flatMap(readAccess::teacherScope)
                    .zipWith(institutions)
                    .map(both -> new StatementReader(true, both.getT1(), both.getT2()));
            });
    }

    public boolean sees(Statement statement) {
        return scope == null || scope.allows(statement);
    }

    /** A statement outside the teacher's scope is refused, not hidden behind a 404. */
    public Mono<Statement> require(Statement statement) {
        return sees(statement)
            ? Mono.just(statement)
            : Mono.error(ApiException.forbidden("Teacher is not assigned to this statement"));
    }
}
