package ao.creativemode.kixi.exams.dto.statement;

import ao.creativemode.kixi.exams.model.Statement;

/**
 * One statement in the student's catalog ({@code GET /api/v1/statements/catalog}).
 *
 * <p>Its own projection rather than the staff {@code StatementSummary} (#170): how
 * sure the OCR was ({@code ocrConfidence}), whether a teacher still has to review
 * it ({@code needsReview}) and where it came from ({@code source}) only matter to
 * whoever edits the statement. {@code /full} already kept them from students
 * (#163); the catalog, which is the student's listing, kept sending them.
 * {@code visible} is left out too: the catalog only ever lists visible ones.
 *
 * <p>{@code questionCount} (#178) counts the questions that are not deleted, so
 * the detail screen does not have to fetch the whole paper to show one number.
 */
public record CatalogStatementSummary(
        Long id,
        String title,
        String examType,
        Integer durationMinutes,
        String variant,
        Double totalMaxScore,
        Long schoolYearId,
        Long termId,
        Long subjectId,
        Long classId,
        Long institutionId,
        Long courseId,
        long questionCount
) {
    public static CatalogStatementSummary from(Statement statement, long questionCount) {
        return new CatalogStatementSummary(
                statement.getId(),
                statement.getTitle(),
                statement.getExamType(),
                statement.getDurationMinutes(),
                statement.getVariant(),
                statement.getTotalMaxScore(),
                statement.getSchoolYearId(),
                statement.getTermId(),
                statement.getSubjectId(),
                statement.getClassId(),
                statement.getInstitutionId(),
                statement.getCourseId(),
                questionCount);
    }
}
