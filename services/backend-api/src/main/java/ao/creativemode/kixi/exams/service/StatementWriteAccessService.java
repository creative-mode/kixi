package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.institutions.service.InstitutionAccessService;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Whether an account may change a statement.
 *
 * <p>Lives here rather than inside {@link StatementService} because writing to a
 * statement is not only a matter of the statement: its questions, its options
 * and its answer key are all statement-scoped, and a rule that has to be
 * reached through a statement service would end up copied into each of them.
 *
 * <p>An administrator may do anything. A teacher only the statements of the
 * classes and subjects they were assigned to.
 */
@Service
public class StatementWriteAccessService {

    private final InstitutionAccessService accessService;

    public StatementWriteAccessService(InstitutionAccessService accessService) {
        this.accessService = accessService;
    }

    public Mono<Statement> requireCanWrite(Statement statement, Long accountId, boolean admin) {
        if (statement.getInstitutionId() == null) {
            if (statement.getClassId() == null) {
                // Statements created before the institution model (the OCR flows)
                // carry no school and no class, so there is nothing to weigh
                // them against. Backfilling them is a separate concern.
                return Mono.just(statement);
            }
            // No school to check them against, but the class is still on them.
            // Returning straight away let any teacher edit, delete or approve a
            // statement sitting in a class they do not teach.
            return accessService
                .requireAssignedTo(accountId, admin, statement.getClassId(), statement.getSubjectId())
                .thenReturn(statement);
        }
        if (statement.getClassId() == null && !admin) {
            // A school statement without a class is not scoped to any class, so
            // no teaching assignment can be checked against it. Only an
            // administrator may build one (ManualStatementService), and the
            // rule has to read the same way on the way back in: otherwise any
            // teacher affiliated to the school, holding no assignment at all,
            // could approve or delete a statement the school made on purpose.
            return Mono.error(ApiException.forbidden(
                "Only an administrator may change a statement without a class"));
        }
        return accessService
            .requireCanAuthor(
                accountId,
                admin,
                statement.getInstitutionId(),
                statement.getSubjectId(),
                statement.getClassId()
            )
            .thenReturn(statement);
    }
}