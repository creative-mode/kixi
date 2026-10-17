package ao.creativemode.kixi.institutions.dto.profile;

import java.time.LocalDateTime;

/**
 * What anyone signed in may read about a school (issue #331): its identity, the badge and a few
 * counts. Nothing internal (no audit columns, no who verified it).
 */
public record InstitutionProfile(
        Long id,
        String code,
        String name,
        String shortName,
        String logo,
        boolean verified,
        LocalDateTime verifiedAt,
        long teacherCount,
        long studentCount,
        long statementCount,
        long roomCount
) { }
