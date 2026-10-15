package ao.creativemode.kixi.shared.service;

import reactor.core.publisher.Mono;

/**
 * What still hangs from an academic record, so the structure can refuse to remove or change it
 * from under the people who depend on it (issue #253).
 *
 * <p>A door rather than a direct read because enrollments and teaching assignments live in
 * {@code institutions} and the structure being protected lives in {@code academic}, which may only
 * depend on {@code shared}. Same reason {@code TeachingAssignmentAuthorizer} exists.</p>
 *
 * <p>Every count is of <em>active</em> rows. Nothing here cascades: a record with dependents is
 * refused with a message that says how many, and the owner moves them first.</p>
 */
public interface AcademicDependencyReader {

    /** Active enrollments of a class. */
    Mono<Long> activeEnrollmentsOfClass(Long classId);

    /** Active teaching assignments of a class. */
    Mono<Long> activeAssignmentsOfClass(Long classId);

    /** Active teaching assignments of a subject, in any class. */
    Mono<Long> activeAssignmentsOfSubject(Long subjectId);

    /**
     * Active assignments that only exist because a curriculum entry covers them: of {@code subjectId}
     * in classes of {@code courseId} whose grade the entry applies to ({@code grade} null = all), and
     * that no other active entry of the same course and subject also covers.
     */
    Mono<Long> assignmentsDependingOnCurriculum(Long courseId, Long subjectId, Integer grade, Long ignoredLinkId);
}
