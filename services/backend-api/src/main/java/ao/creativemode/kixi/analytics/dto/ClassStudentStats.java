package ao.creativemode.kixi.analytics.dto;

/**
 * One student of a class, for the teacher's "who needs help" list.
 *
 * <p>{@code status} is {@code AT_RISK} when the average is under half the score,
 * {@code NO_ACTIVITY} when the student finished nothing yet, and {@code OK}
 * otherwise. {@code lastFinishedAt} is ISO-8601 with offset.</p>
 */
public record ClassStudentStats(
        long accountId,
        String name,
        long finishedSimulations,
        Double averageScorePercent,
        String lastFinishedAt,
        String status
) {
}
