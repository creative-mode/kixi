package ao.creativemode.kixi.analytics.dto;

/** An auto-graded question of a class, ordered from the most missed. */
public record HardQuestion(
        long id,
        int number,
        String text,
        String questionType,
        Double maxScore,
        long statementId,
        String statementTitle,
        long attempts,
        long correct,
        Double correctPercent
) {
}
