package ao.creativemode.kixi.institutions.dto.profile;

import java.util.List;

/**
 * A teacher as shown on a school's profile. No e-mail, no employee number. {@code verified} is
 * derived: a teacher of a verified school is verified by that school. {@code accountId} is what
 * opens the teacher's profile: the feed and the profile routes work by account.
 *
 * <p>{@code schools} are the active schools the teacher is affiliated with (issue #406): on a
 * school's own profile it is that one school, and the search lists every school, so the same
 * teacher at two schools is one card, not two.</p>
 */
public record TeacherCard(
        Long id,
        Long accountId,
        String displayName,
        String photo,
        String specialty,
        boolean verified,
        List<InstitutionCard> schools
) { }
