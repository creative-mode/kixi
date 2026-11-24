package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.institutions.service.TeachingAssignmentService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Set;

/**
 * Loads the {@link StatementReadScope} of a teacher: one query for the whole
 * request, then every row is weighed in memory against it.
 */
@Service
public class StatementReadAccessService {

    private final TeachingAssignmentService teachingAssignments;

    private final ObjectProvider<StatementRepository> statements;

    public StatementReadAccessService(TeachingAssignmentService teachingAssignments, ObjectProvider<StatementRepository> statements) {
        this.teachingAssignments = teachingAssignments;
        this.statements = statements;
    }

    /** As instituições de que a conta é coordenadora. */
    public Mono<Set<Long>> coordinatedInstitutions(Long accountId) {
        // ObjectProvider: as fatias de teste que importam este serviço sem repositórios não têm de o ter (só o coordenador o usa).
        return Flux.defer(() -> {
            StatementRepository repository = statements.getIfAvailable();
            return repository == null ? Flux.<Long>empty() : repository.findCoordinatedInstitutionIds(accountId);
        }).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Mono<StatementReadScope> teacherScope(Long accountId) {
        return Flux.defer(() -> teachingAssignments.findMineForAccount(accountId))
            .map(assignment -> new StatementReadScope.Pair(assignment.classId(), assignment.subjectId()))
            .collectList()
            .map(StatementReadScope::new);
    }
}
