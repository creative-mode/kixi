package ao.creativemode.kixi.shared.service;

import reactor.core.publisher.Mono;

/** Checks whether an account may teach or author for an institution. */
public interface TeachingAssignmentAuthorizer {

    Mono<Void> requireAssignedTo(Long accountId, boolean admin, Long classId, Long subjectId);

    Mono<Void> requireCanAuthor(Long accountId, boolean admin, Long institutionId, Long subjectId);
}
