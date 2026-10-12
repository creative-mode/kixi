package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.institutions.repository.TeachingAssignmentRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * The tutor voice of the teacher who wrote the statement (issue #116).
 *
 * <p>Lives in {@code exams} because that is the module that owns the statement, and
 * because {@code exams → institutions} is the only direction already in use between
 * them. {@code chat} may not reach {@code institutions} at all and {@code institutions}
 * may not reach {@code exams}, so a bridge anywhere else closes a cycle.</p>
 *
 * <p>Whose voice it is: the statement's author. Not the caller. The tutor is used by
 * students, who hold no teaching assignments, so reading the style from the signed-in
 * account would give every student the empty style of somebody who never taught.</p>
 */
@Service
public class TutorStyleService {

    private final TeachingAssignmentRepository assignments;

    public TutorStyleService(TeachingAssignmentRepository assignments) {
        this.assignments = assignments;
    }

    /**
     * The raw {@code tutor_style} of the author for that class and subject (the year is
     * the class's), or an empty string when there is nobody whose voice to borrow.
     *
     * <p>Empty is the normal case, not a failure: a statement without an author, class or
     * subject has nobody to speak for, and an author who never wrote a style has
     * none to give. Either way the prompt is composed exactly as it was before this
     * issue, which is the behaviour {@code compose(null)} guarantees byte for byte.</p>
     */
    public Mono<String> styleOf(Statement statement) {
        // created_by is the author's account; the repository goes through teachers.
        Long authorAccountId = statement.getCreatedBy();
        Long classId = statement.getClassId();
        Long subjectId = statement.getSubjectId();

        if (authorAccountId == null || classId == null || subjectId == null) {
            return Mono.just("");
        }

        return assignments
            .findActiveForAuthor(authorAccountId, classId, subjectId)
            // flatMap and justOrEmpty, not map: an assignment with no style is not a
            // failure, and a mapper that returns null throws instead of saying so.
            .flatMap(assignment -> Mono.justOrEmpty(assignment.getTutorStyle()))
            .map(String::trim)
            .filter(style -> !style.isEmpty())
            .defaultIfEmpty("");
    }
}
