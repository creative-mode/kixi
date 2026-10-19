package ao.creativemode.kixi.institutions.dto.profile;

import java.util.List;

/**
 * A person as another signed-in account sees them (issue #336). When {@code restricted} is true the
 * person chose not to show more than name and photo to this viewer: schools, level and badges come
 * back empty. Never e-mail, phone, grades or date of birth.
 *
 * <p>{@code level}/{@code xp}/{@code badges} are a student's gamification stats: a teacher never
 * earns any of these, so they come back {@code null}/empty for one, and {@code specialty} (the
 * teacher's own attribute, already shown in {@link TeacherCard}) comes back instead (issue #371).</p>
 */
public record PersonProfile(
        Long userId,
        String displayName,
        String photo,
        String kind,
        boolean verified,
        boolean restricted,
        String visibility,
        List<InstitutionCard> schools,
        Integer level,
        Long xp,
        List<String> badges,
        String specialty
) { }
