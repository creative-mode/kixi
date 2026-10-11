package ao.creativemode.kixi.analytics.dto;

/** {@code ClassStats}. */
public record ClassStats(
        long id,
        String code,
        int grade,
        String course,
        String institution,
        long students,
        long teachers,
        long statements,
        long finishedSimulations,
        Double averageScorePercent,
        Long averageTimeSeconds
) {
}
