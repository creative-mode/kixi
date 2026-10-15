package ao.creativemode.kixi.institutions.dto.structure;

import ao.creativemode.kixi.institutions.dto.structure.AcademicRefs.SchoolYearRef;
import java.util.List;

/** Where a teacher teaches, school by school, including the schools they belong to without classes yet. */
public record TeacherOverviewResponse(TeacherRef teacher, List<InstitutionNode> institutions) {

    public record TeacherRef(Long id, String firstName, String lastName) { }

    public record AssignmentNode(
            Long assignmentId, Long classId, String classCode, Integer grade,
            Long courseId, String courseName,
            Long subjectId, String subjectCode, String subjectName,
            SchoolYearRef schoolYear) { }

    public record InstitutionNode(Long id, String code, String name, List<AssignmentNode> assignments) { }
}
