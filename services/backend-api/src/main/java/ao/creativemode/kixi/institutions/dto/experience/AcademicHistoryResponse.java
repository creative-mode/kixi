package ao.creativemode.kixi.institutions.dto.experience;

import ao.creativemode.kixi.institutions.dto.experience.ExperienceResponse.ClassRef;
import ao.creativemode.kixi.institutions.dto.experience.ExperienceResponse.InstitutionRef;
import ao.creativemode.kixi.institutions.dto.experience.ExperienceResponse.SchoolYearRef;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The signed-in student's own results in one school year. Private: it is only ever built for the
 * authenticated account, from {@code GET /api/v1/me/academic-history}, and no route takes another
 * account's id.
 *
 * <p>{@code average}, {@code best} and {@code last} are percentages (0-100) of the statement's
 * maximum score; an exam carries the raw {@code score} and {@code max}.</p>
 */
public record AcademicHistoryResponse(
        SchoolYearRef schoolYear,
        InstitutionRef institution,
        ClassRef schoolClass,
        List<SubjectResult> subjects,
        List<ExamResult> exams,
        Totals totals
) {

    public record SubjectResult(
            Long subjectId, String subject, long simulations, Double average, Double best, Double last) { }

    public record ExamResult(
            Long id, String title, String subject, Double score, Double max, OffsetDateTime date, String status) { }

    public record Totals(long simulations, long exams, Double average) { }
}
