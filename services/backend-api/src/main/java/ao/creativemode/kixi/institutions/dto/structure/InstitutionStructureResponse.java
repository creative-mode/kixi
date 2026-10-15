package ao.creativemode.kixi.institutions.dto.structure;

import ao.creativemode.kixi.institutions.dto.structure.AcademicRefs.ClassSubject;
import ao.creativemode.kixi.institutions.dto.structure.AcademicRefs.SchoolYearRef;
import java.util.List;

/**
 * The school as a tree: courses with their curriculum and classes, the classes with the subjects they
 * have and who teaches them, plus the affiliated teachers, counters and what is incomplete (#253).
 */
public record InstitutionStructureResponse(
        InstitutionRef institution,
        SchoolYearRef schoolYear,
        List<CourseNode> courses,
        List<TeacherNode> teachers,
        Counts counts,
        List<Warning> warnings
) {

    public record InstitutionRef(Long id, String code, String name, String short_name) { }

    public record CurriculumEntry(
            Long linkId, Long subjectId, String code, String name, String shortName, Integer grade) { }

    public record ClassNode(
            Long id, String code, Integer grade, long studentCount, List<ClassSubject> subjects) { }

    public record CourseNode(
            Long id, String code, String name, String description,
            List<CurriculumEntry> curriculum, List<ClassNode> classes) { }

    public record TeacherNode(Long id, String firstName, String lastName, String email, long assignments) { }

    public record Counts(long courses, long classes, long subjects, long teachers, long students) { }

    public record Warning(
            String code, String message, Long courseId, Long classId, Long subjectId, Long teacherId) { }
}
