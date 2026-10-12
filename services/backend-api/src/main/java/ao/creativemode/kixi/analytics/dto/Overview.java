package ao.creativemode.kixi.analytics.dto;

/** {@code Overview} in types/analytics.ts. */
public record Overview(
        String scope,
        long institutions,
        long classes,
        long students,
        long teachers,
        Statements statements,
        Simulations simulations,
        Double averageScorePercent,
        Double passRatePercent,
        Long averageTimeSeconds
) {
    /** {@code OverviewStatements}. */
    public record Statements(long total, long published, long needingReview, long missingAnswerKey) {
    }

    /** {@code OverviewSimulations}. */
    public record Simulations(long total, long finished, long inProgress, long cancelled, long uniqueStudents) {
    }
}
