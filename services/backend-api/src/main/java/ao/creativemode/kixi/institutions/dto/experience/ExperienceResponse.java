package ao.creativemode.kixi.institutions.dto.experience;

import java.util.List;

/**
 * A person's schooling, year by year, as other signed-in accounts see it. Public part of the
 * profile: places, courses, classes and (for a teacher) subjects. Never grades or results; those
 * are {@link AcademicHistoryResponse} and only ever go to the person themselves.
 *
 * <p>{@code role} is STUDENT or TEACHER. {@code restricted} is true when the person only lets this
 * viewer see name and photo, and then {@code entries} is empty.</p>
 */
public record ExperienceResponse(String role, boolean restricted, List<Entry> entries) {

    public record Entry(
            SchoolYearRef schoolYear,
            InstitutionRef institution,
            CourseRef course,
            List<ClassRef> classes,
            List<SubjectRef> subjects,
            boolean current
    ) { }

    public record SchoolYearRef(Long id, String label) { }

    public record InstitutionRef(Long id, String name, String code) { }

    public record CourseRef(Long id, String name) { }

    public record ClassRef(Long id, String name, Integer grade) { }

    public record SubjectRef(Long id, String name) { }
}
