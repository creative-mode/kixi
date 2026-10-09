package ao.creativemode.kixi.exams.dto.statement;

/**
 * What the student asked the catalog for. Every filter is optional; an absent one
 * does not narrow the result.
 *
 * <p>{@code grade} is the "classe" (10.ª, 11.ª…), read from the class the statement
 * was set for; {@code classId} is one specific "turma".</p>
 *
 * @param scope {@code mine} (default) keeps the listing to the student's own school
 *              when no {@code institutionId} is given; {@code all} lifts that default
 * @param sort  {@code relevance} (default: own class first, then own course, then the
 *              rest, newest first within each), {@code recent}, {@code oldest} or {@code title}
 */
public record StatementCatalogFilter(
        Long institutionId,
        Long courseId,
        Long classId,
        Integer grade,
        Long subjectId,
        Long schoolYearId,
        Long termId,
        String examType,
        String query,
        String scope,
        String sort,
        int page,
        int size
) {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
}
