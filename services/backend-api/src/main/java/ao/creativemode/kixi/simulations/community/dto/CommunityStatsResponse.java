package ao.creativemode.kixi.simulations.community.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * How the community did on one statement, with the caller's own attempt for
 * the "you vs. the average" comparison (issue #119).
 *
 * <p>Only finished simulations with a grade count ({@code finalScore IS NOT
 * NULL}); answers still waiting for review stay out of the per-question
 * denominators instead of counting as wrong. Scores are bucketed relative to
 * the statement's {@code totalMaxScore} so papers of any scale render.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommunityStatsResponse(
        Long statementId,
        int sampleSize,
        Double meanScore,
        Double medianScore,
        List<ScoreBucket> scoreDistribution,
        List<QuestionStats> questions,
        Double avgTimeSpentSeconds,
        int timeSampleSize,
        MineSummary mine
) {
    /** One slice of the grade histogram, from {@code from} (inclusive) to {@code to}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScoreBucket(int index, Double from, Double to, long count) { }

    /**
     * @param attempts    objective attempts with a known outcome ({@code isCorrect IS NOT NULL})
     * @param correctCount among those attempts
     * @param pctCorrect  0–100, null when no objective attempt exists yet
     * @param avgScore    over graded answers only, null when none is graded yet
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuestionStats(
            Long questionId,
            Integer number,
            Double maxScore,
            long attempts,
            long correctCount,
            Double pctCorrect,
            Double avgScore,
            MineAnswer mine
    ) { }

    /** The caller's own answer for one question. Never inferred for open text. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MineAnswer(Float scoreObtained, Boolean isCorrect) { }

    /** The caller's latest finished attempt, null when they never finished this paper. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MineSummary(Long simulationId, Double finalScore) { }
}
