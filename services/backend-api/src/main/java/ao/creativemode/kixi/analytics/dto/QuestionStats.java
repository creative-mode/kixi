package ao.creativemode.kixi.analytics.dto;

/**
 * {@code QuestionStats}. {@code correctPercent} counts an unanswered question as
 * wrong, and is {@code null} for a question that is not auto-graded (one with no
 * options, which the teacher corrects by hand).
 */
public record QuestionStats(
        long id,
        int number,
        String text,
        String questionType,
        Double maxScore,
        long attempts,
        long answered,
        long correct,
        Double correctPercent
) {
}
