package ao.creativemode.kixi.shared.service;

import reactor.core.publisher.Flux;

/**
 * The classes an account is allowed to be measured against.
 *
 * <p>A door because the answer lives in {@code institutions} and the dashboard lives in
 * {@code analytics}: without it the module diagram would gain an edge, which is the same
 * reason {@code TeachingAssignmentAuthorizer} exists.</p>
 *
 * <p>This is about <em>teaching</em>, not about being enrolled. A student is seated; a
 * teacher is assigned to a class and answerable for it. The dashboard asks a teacher for
 * their students' delivery times and learning gaps, which is the second thing.</p>
 */
public interface TeachingScopeReader {

    /**
     * The ids of the classes the account teaches, newest assignment first.
     *
     * <p>An account that teaches nothing gets an empty flux, which the caller turns into
     * a scope that matches no class — never one that matches every class.</p>
     */
    Flux<Long> classIdsTaughtBy(Long accountId);
}