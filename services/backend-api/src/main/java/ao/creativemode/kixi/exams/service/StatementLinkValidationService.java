package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.CourseRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import ao.creativemode.kixi.academic.repository.TermRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Checks that the references on a statement hang together with the academic
 * structure.
 *
 * <p>The foreign keys would already reject an id that does not exist, but not a
 * statement whose class belongs to last year's school year while the statement
 * claims this one. That mismatch is what makes a teaching assignment
 * authorisation land on the wrong year, so it is worth refusing up front.
 *
 * <p>Every reference is optional except the ones the request marks as required:
 * a school-wide statement carries a subject and no class, a term, or a course.
 */
@Service
public class StatementLinkValidationService {

    private final SchoolYearRepository schoolYears;
    private final TermRepository terms;
    private final SubjectRepository subjects;
    private final CourseRepository courses;
    private final ClassRepository classes;

    public StatementLinkValidationService(
        SchoolYearRepository schoolYears,
        TermRepository terms,
        SubjectRepository subjects,
        CourseRepository courses,
        ClassRepository classes
    ) {
        this.schoolYears = schoolYears;
        this.terms = terms;
        this.subjects = subjects;
        this.courses = courses;
        this.classes = classes;
    }

    public Mono<Void> validate(
        Long schoolYearId,
        Long termId,
        Long classId,
        Long subjectId,
        Long courseId
    ) {
        return mustExist(schoolYears::findByIdAndDeletedAtIsNull, schoolYearId, "The school year")
            .then(mustExist(terms::findByIdAndDeletedAtIsNull, termId, "The term"))
            .then(mustExist(subjects::findByIdAndDeletedAtIsNull, subjectId, "The subject"))
            .then(mustExist(courses::findByIdAndDeletedAtIsNull, courseId, "The course"))
            .then(mustBelongToItsOwnYearAndCourse(classId, schoolYearId, courseId));
    }

    private <T> Mono<Void> mustExist(Function<Long, Mono<T>> lookup, Long id, String what) {
        if (id == null) {
            return Mono.empty();
        }
        return Mono.defer(() -> lookup.apply(id))
            .hasElement()
            .flatMap(found -> found
                ? Mono.<Void>empty()
                : Mono.error(ApiException.unprocessableEntity(what + " does not exist")));
    }

    private Mono<Void> mustBelongToItsOwnYearAndCourse(Long classId, Long schoolYearId, Long courseId) {
        if (classId == null) {
            return Mono.empty();
        }
        return Mono.defer(() -> classes.findByIdAndDeletedAtIsNull(classId))
            .switchIfEmpty(Mono.error(ApiException.unprocessableEntity("The class does not exist")))
            .flatMap(klass -> agreeWith(klass, schoolYearId, courseId));
    }

    private Mono<Void> agreeWith(Class klass, Long schoolYearId, Long courseId) {
        if (schoolYearId != null && !klass.getSchoolYearId().equals(schoolYearId)) {
            return Mono.error(ApiException.unprocessableEntity(
                "The school year must be the one of the class (" + klass.getSchoolYearId() + ")"));
        }
        if (courseId != null && !klass.getCourseId().equals(courseId)) {
            return Mono.error(ApiException.unprocessableEntity(
                "The course must be the one of the class (" + klass.getCourseId() + ")"));
        }
        return Mono.empty();
    }
}
