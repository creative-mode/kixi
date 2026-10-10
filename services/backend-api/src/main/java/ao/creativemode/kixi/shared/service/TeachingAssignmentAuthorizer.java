package ao.creativemode.kixi.shared.service;

import reactor.core.publisher.Mono;

/** Checks whether an account is assigned to teach a class and subject. */
public interface TeachingAssignmentAuthorizer {

    Mono<Void> requireAssignedTo(Long accountId, boolean admin, Long classId, Long subjectId);
}
