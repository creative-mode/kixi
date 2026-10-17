package ao.creativemode.kixi.institutions.dto.profile;

/**
 * A teacher as shown on a school's profile. No e-mail, no employee number. {@code verified} is
 * derived: a teacher of a verified school is verified by that school.
 */
public record TeacherCard(
        Long id,
        String displayName,
        String photo,
        String specialty,
        boolean verified
) { }
