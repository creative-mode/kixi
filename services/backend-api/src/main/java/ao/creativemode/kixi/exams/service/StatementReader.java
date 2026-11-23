package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
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
 */
public record StatementReader(boolean staff, StatementReadScope scope) {

    /** An administrator: staff with no scope to weigh a statement against. */
    public static final StatementReader ADMIN = new StatementReader(true, null);

    /** A student, or any reader that is not staff: published statements only, no answer key. */
    public static final StatementReader STUDENT = new StatementReader(false, null);

    /** Resolves the reader of the current request. */
    public static Mono<StatementReader> current(CurrentAccountService currentAccount, StatementReadAccessService readAccess) {
        return Mono.zip(currentAccount.hasAnyRole("ADMIN"), currentAccount.hasAnyRole("TEACHER"))
            .flatMap(roles -> {
                if (roles.getT1()) return Mono.just(ADMIN);
                if (!roles.getT2()) return Mono.just(STUDENT);
                return currentAccount.requiredAccountId()
                    .flatMap(readAccess::teacherScope)
                    .map(scope -> new StatementReader(true, scope));
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
