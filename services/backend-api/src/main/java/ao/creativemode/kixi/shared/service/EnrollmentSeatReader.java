package ao.creativemode.kixi.shared.service;

import reactor.core.publisher.Flux;

/**
 * The seats an account actually holds, as issue #117 needs to read them.
 *
 * <p>Which cohort a student is ranked in cannot be answered by a profile. {@code /me}
 * reports the most recent enrollment whether or not it is cancelled, and its school can
 * come from an institutional link with no enrollment behind it, so the caller has to ask
 * the enrollments themselves.</p>
 *
 * <p>A door rather than a direct call because enrollments live in {@code institutions}
 * and the tutor/ranking lives in {@code simulations}: without this the module diagram
 * would gain an edge, and the same reason {@code TeachingAssignmentAuthorizer} exists.</p>
 */
public interface EnrollmentSeatReader {

    /**
     * The account's ACTIVE seats, newest first.
     *
     * <p>Newest first is the rule, not an accident of ordering: a student who moved keeps
     * the old row ACTIVE, and the most recent row is the school they are at now. The id
     * breaks ties between rows joined in the same instant.</p>
     */
    Flux<Seat> activeSeatsOf(Long accountId);

    /**
     * One seat and where it sits.
     *
     * @param institutionId null when the class belongs to no school, which is still a seat
     *                      for a class ranking even though it is nothing for a school one
     */
    record Seat(Long classId, Long schoolYearId, Long institutionId) { }
}