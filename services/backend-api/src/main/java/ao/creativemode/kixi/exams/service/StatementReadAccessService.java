package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.institutions.service.TeachingAssignmentService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Loads the {@link StatementReadScope} of a teacher: one query for the whole
 * request, then every row is weighed in memory against it.
 */
@Service
public class StatementReadAccessService {

    private final TeachingAssignmentService teachingAssignments;

    public StatementReadAccessService(TeachingAssignmentService teachingAssignments) {
        this.teachingAssignments = teachingAssignments;
    }

    public Mono<StatementReadScope> teacherScope(Long accountId) {
        return Flux.defer(() -> teachingAssignments.findMineForAccount(accountId))
            .map(assignment -> new StatementReadScope.Pair(assignment.classId(), assignment.subjectId()))
            .collectList()
            .map(StatementReadScope::new);
    }
}
