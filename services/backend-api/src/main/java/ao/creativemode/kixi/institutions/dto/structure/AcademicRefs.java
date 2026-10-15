package ao.creativemode.kixi.institutions.dto.structure;

/** Small references shared by the academic read models (issue #253). */
public final class AcademicRefs {

    private AcademicRefs() { }

    public record SchoolYearRef(Long id, Integer startYear, Integer endYear) { }

    /** A teacher assigned to a subject of a class; {@code assignmentId} is what removes or edits it. */
    public record AssignedTeacher(Long assignmentId, Long id, String firstName, String lastName) { }

    /** A subject a class has through its course, with who teaches it (empty for callers that may not see it). */
    public record ClassSubject(Long subjectId, String code, String name, java.util.List<AssignedTeacher> teachers) { }
}
