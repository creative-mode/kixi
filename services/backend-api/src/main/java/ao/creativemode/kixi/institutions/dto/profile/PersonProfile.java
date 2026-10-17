package ao.creativemode.kixi.institutions.dto.profile;

import java.util.List;

/**
 * A person as another signed-in account sees them (issue #336). When {@code restricted} is true the
 * person chose not to show more than name and photo to this viewer: schools, level and badges come
 * back empty. Never e-mail, phone, grades or date of birth.
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
        List<String> badges
) { }
