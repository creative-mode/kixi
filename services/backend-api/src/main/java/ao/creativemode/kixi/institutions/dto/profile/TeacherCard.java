package ao.creativemode.kixi.institutions.dto.profile;

/**
 * A teacher as shown on a school's profile. No e-mail, no employee number. {@code verified} is
 * derived: a teacher of a verified school is verified by that school. {@code accountId} is what
 * opens the teacher's profile: the feed and the profile routes work by account.
 */
public record TeacherCard(
        Long id,
        Long accountId,
        String displayName,
        String photo,
        String specialty,
        boolean verified
) { }
