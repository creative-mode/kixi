package ao.creativemode.kixi.shared.service;

import reactor.core.publisher.Mono;

/**
 * The subjects a school teaches. The curriculum of a course is contained in this catalog, so
 * putting a subject in a course has to make sure it is in the catalog of the course's school
 * (issue #253). A door for the same reason as {@link AcademicDependencyReader}: the catalog lives in
 * {@code institutions}, the curriculum in {@code academic}.
 */
public interface InstitutionSubjectCatalog {

    /** Puts the subject in the school's catalog, restoring a removed link instead of duplicating it. */
    Mono<Void> ensureInCatalog(Long institutionId, Long subjectId);
}
