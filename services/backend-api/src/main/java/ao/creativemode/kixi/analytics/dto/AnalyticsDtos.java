package ao.creativemode.kixi.analytics.dto;

import java.util.List;

/**
 * The dashboard contract of issue #110.
 *
 * <p>These records mirror {@code types/analytics.ts} of the frontend one for one, and
 * the shape is not ours to change: the dashboard renders these fields by name and a
 * renamed key is a blank panel. Two rules run through all of them:</p>
 * <ul>
 *   <li>a percentage is 0–100 with one decimal, never an integer and never rounded away;</li>
 *   <li>no data is {@code null}, not zero — a school with nobody who sat the paper has no
 *       average, and a zero would say everybody failed.</li>
 * </ul>
 */
public final class AnalyticsDtos {

    private AnalyticsDtos() { }

    /** {@code scope} in {@link Overview}. */
    public enum Scope {
        ADMIN, TEACHER
    }

    public record Overview(
            Scope scope,
            int institutions,
            int classes,
            int students,
            int teachers,
            OverviewStatements statements,
            OverviewSimulations simulations,
            Double averageScorePercent,
            Double passRatePercent,
            Double averageTimeSeconds
    ) { }

    public record OverviewStatements(
            int total,
            int published,
            int needingReview,
            int missingAnswerKey
    ) { }

    public record OverviewSimulations(
            int total,
            int finished,
            int inProgress,
            int cancelled,
            int uniqueStudents
    ) { }

    /** @param date yyyy-MM-dd, UTC, the day a simulation was finished */
    public record ActivityPoint(
            String date,
            int finished,
            Double averageScorePercent
    ) { }

    public record InstitutionStats(
            long id,
            String code,
            String name,
            int courses,
            int classes,
            int students,
            int finishedSimulations,
            Double averageScorePercent,
            Double averageTimeSeconds
    ) { }

    public record ClassStats(
            long id,
            String code,
            int grade,
            String course,
            String institution,
            int students,
            int teachers,
            int statements,
            int finishedSimulations,
            Double averageScorePercent,
            Double averageTimeSeconds
    ) { }

    public record StatementStats(
            long id,
            String title,
            String examType,
            String classCode,
            String subject,
            boolean published,
            boolean needsReview,
            boolean answerKeyComplete,
            int questions,
            int finishedSimulations,
            int uniqueStudents,
            Double averageScorePercent,
            Double passRatePercent,
            Double averageTimeSeconds
    ) { }

    /** @param correctPercent null for a question that is not auto-graded */
    public record QuestionStats(
            long id,
            int number,
            String text,
            String questionType,
            Double maxScore,
            int attempts,
            int answered,
            int correct,
            Double correctPercent
    ) { }
}