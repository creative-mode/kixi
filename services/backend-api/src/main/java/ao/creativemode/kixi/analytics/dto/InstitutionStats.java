package ao.creativemode.kixi.analytics.dto;

/** {@code InstitutionStats}. */
public record InstitutionStats(
        long id,
        String code,
        String name,
        long courses,
        long classes,
        long students,
        long finishedSimulations,
        Double averageScorePercent,
        Long averageTimeSeconds
) {
}
