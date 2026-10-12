package ao.creativemode.kixi.shared.service;

import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * What an account may read and write in the social feed (issue #120).
 *
 * <p>The feed is isolated by school. A class, a course and a school year are not
 * enough on their own: course and school-year ids are not scoped to one
 * institution, so filtering on them alone lets a request from one school read
 * another's. The institution is the hard boundary; class, course and school
 * year decide which posts <em>inside</em> that boundary a caller may see.</p>
 *
 * <p>A door rather than a direct call because enrollments and teaching
 * assignments live in {@code institutions} and the feed lives in {@code feed},
 * for the same reason {@code TeachingAssignmentAuthorizer} exists.</p>
 */
public interface FeedScopeAuthorizer {

    /**
     * The scope an account may read, write into and moderate.
     *
     * <p>A student's scope comes from their active enrollments: the
     * institution(s) and class(es) they actually hold a seat in, and the
     * courses and school years those classes belong to. A teacher's scope
     * comes the same way from their active teaching assignments. An account
     * with neither gets {@link FeedScope#NONE}.</p>
     */
    Mono<FeedScope> scopeOf(Long accountId);

    /** One class a caller may author a post into, or empty when it is not theirs. */
    Mono<ClassScope> classScope(Long accountId, Long classId);

    /**
     * The classes an account teaches, for moderation. A teacher may hide a
     * report in their own classes; only an ADMIN may do so everywhere, and
     * that is a role check the service makes on its own, not this set.
     */
    Mono<Set<Long>> taughtClassesOf(Long accountId);

    /**
     * Where a caller is allowed to see and write feed content.
     *
     * <p>{@code placements} keeps each held class's institution, course and
     * school year together. The flat sets alone cannot say <em>whose</em>
     * course a course id is, so an account in school A (course 100) and school
     * B would otherwise be cleared for course 100 inside school B too.</p>
     */
    record FeedScope(
            Set<Long> institutionIds,
            Set<Long> classIds,
            Set<Long> courseIds,
            Set<Long> schoolYearIds,
            Set<Placement> placements) {

        public static final FeedScope NONE =
                new FeedScope(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

        /**
         * Builds the placements as every institution paired with every course and
         * school year; only right when the sets all belong to the same institution.
         */
        public FeedScope(
                Set<Long> institutionIds, Set<Long> classIds, Set<Long> courseIds, Set<Long> schoolYearIds) {
            this(institutionIds, classIds, courseIds, schoolYearIds,
                    crossProduct(institutionIds, courseIds, schoolYearIds));
        }

        private static Set<Placement> crossProduct(
                Set<Long> institutionIds, Set<Long> courseIds, Set<Long> schoolYearIds) {
            Set<Placement> all = new java.util.HashSet<>();
            for (Long institution : institutionIds) {
                for (Long course : courseIds) {
                    all.add(new Placement(institution, course, null));
                }
                for (Long year : schoolYearIds) {
                    all.add(new Placement(institution, null, year));
                }
            }
            return all;
        }

        public boolean isEmpty() {
            return classIds.isEmpty();
        }

        /** Whether a post with these coordinates falls inside this scope. */
        public boolean covers(Long institutionId, Long classId, Long courseId, Long schoolYearId) {
            if (!institutionIds.contains(institutionId)) {
                return false;
            }
            if (classIds.contains(classId)) {
                return true;
            }
            return placements.stream().anyMatch(placement ->
                    institutionId.equals(placement.institutionId())
                            && (courseId != null && courseId.equals(placement.courseId())
                                    || schoolYearId != null && schoolYearId.equals(placement.schoolYearId())));
        }
    }

    /** A course and school year as held inside one specific institution. */
    record Placement(Long institutionId, Long courseId, Long schoolYearId) { }

    /** The institution, course and school year a specific class sits in. */
    record ClassScope(Long classId, Long institutionId, Long courseId, Long schoolYearId) { }
}
