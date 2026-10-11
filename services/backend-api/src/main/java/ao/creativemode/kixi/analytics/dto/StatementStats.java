package ao.creativemode.kixi.analytics.dto;

/** {@code StatementStats}. */
public record StatementStats(
        long id,
        String title,
        String examType,
        String classCode,
        String subject,
        boolean published,
        boolean needsReview,
        boolean answerKeyComplete,
        long questions,
        long finishedSimulations,
        long uniqueStudents,
        Double averageScorePercent,
        Double passRatePercent,
        Long averageTimeSeconds
) {
}
