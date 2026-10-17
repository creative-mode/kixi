package ao.creativemode.kixi.institutions.dto.profile;

/** One school in a search result. */
public record InstitutionCard(
        Long id,
        String code,
        String name,
        String shortName,
        String logo,
        boolean verified
) { }
