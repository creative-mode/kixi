package ao.creativemode.kixi.academic.dto.coursesubject;

/** An entry of a course's curriculum, with the subject it names. {@code id} is the link's own id. */
public record CourseSubjectResponse(
        Long id,
        Long courseId,
        Long subjectId,
        String code,
        String name,
        String shortName,
        Integer grade
) { }
