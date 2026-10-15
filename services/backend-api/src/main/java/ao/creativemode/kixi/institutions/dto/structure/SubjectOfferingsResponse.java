package ao.creativemode.kixi.institutions.dto.structure;

import java.util.List;

/** Where a subject is taught: the schools, and in each one the courses and the classes that have it. */
public record SubjectOfferingsResponse(SubjectRef subject, List<InstitutionNode> institutions) {

    public record SubjectRef(Long id, String code, String name) { }

    public record ClassRef(Long id, String code, Integer grade) { }

    /** One per curriculum entry: {@code grade} null means the whole course. */
    public record CourseNode(Long id, String code, String name, Integer grade, List<ClassRef> classes) { }

    public record InstitutionNode(Long id, String code, String name, List<CourseNode> courses) { }
}
