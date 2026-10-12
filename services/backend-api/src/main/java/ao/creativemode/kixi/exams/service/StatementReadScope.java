package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.exams.model.Statement;
import java.util.List;
import java.util.Objects;

/**
 * The statements a teacher may read, worked out from their teaching
 * assignments (#174).
 *
 * <p>The same rule the manager app applied in the browser
 * ({@code teacherCanWrite} in {@code lib/crud/teacher-scope.ts}), now on the
 * server so the rest of the school never leaves it:
 * <ul>
 *   <li>no class and no school: an OCR statement from before the institution
 *       model, which every teacher has always been able to edit;</li>
 *   <li>a school but no class: only an administrator builds those, so no;</li>
 *   <li>a class without a subject: the teacher has to teach that class;</li>
 *   <li>a class and a subject: the teacher has to teach that exact pair.</li>
 * </ul>
 *
 * <p>The school year needs no check of its own: an assignment is always saved
 * with the year of its class, and a class belongs to one year.
 */
public record StatementReadScope(List<Pair> assignments) {

    /** One class and subject the teacher is assigned to. */
    public record Pair(Long classId, Long subjectId) {}

    /** A teacher with no assignments: only the statements no class owns. */
    public static final StatementReadScope NO_ASSIGNMENTS = new StatementReadScope(List.of());

    public StatementReadScope {
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
    }

    public boolean allows(Statement statement) {
        if (statement == null) return false;
        Long classId = statement.getClassId();
        Long subjectId = statement.getSubjectId();
        if (classId == null) return statement.getInstitutionId() == null;
        if (subjectId == null) {
            return assignments.stream().anyMatch(pair -> classId.equals(pair.classId()));
        }
        return assignments.stream().anyMatch(pair ->
                classId.equals(pair.classId()) && Objects.equals(subjectId, pair.subjectId()));
    }
}
