package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.CourseRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import ao.creativemode.kixi.academic.repository.TermRepository;
import ao.creativemode.kixi.academic.service.CurriculumService;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Checks that the references on a statement hang together with the academic
 * structure, and that the caller is allowed to name no class.
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
    private final CurriculumService curriculum;

    public StatementLinkValidationService(
        SchoolYearRepository schoolYears,
        TermRepository terms,
        SubjectRepository subjects,
        CourseRepository courses,
        ClassRepository classes,
        CurriculumService curriculum
    ) {
        this.schoolYears = schoolYears;
        this.terms = terms;
        this.subjects = subjects;
        this.courses = courses;
        this.classes = classes;
        this.curriculum = curriculum;
    }

    public Mono<Void> validate(
        Long schoolYearId,
        Long termId,
        Long classId,
        Long subjectId,
        Long courseId
    ) {
        return validate(null, schoolYearId, termId, classId, subjectId, courseId);
    }

    /**
     * As above, and the school has to agree with the rest (issue #253): the class and the course
     * must belong to {@code institutionId} when it is given, and the subject has to be in the
     * curriculum of the class. A statement without a class is checked against the school catalog by
     * the access service instead. Applied when a statement is written or edited; the statements
     * already stored are not revisited.
     */
    public Mono<Void> validate(
        Long institutionId,
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
            .then(mustBelongToItsOwnYearAndCourse(classId, schoolYearId, courseId))
            .then(mustBelongToTheSchool(institutionId, classId, courseId))
            .then(mustBeInTheCurriculum(classId, subjectId));
    }

    private Mono<Void> mustBelongToTheSchool(Long institutionId, Long classId, Long courseId) {
        if (institutionId == null) {
            return Mono.empty();
        }
        Mono<Void> classOk = classId == null ? Mono.empty()
            : Mono.defer(() -> classes.findByIdAndDeletedAtIsNull(classId))
                .filter(klass -> !institutionId.equals(klass.getInstitutionId()))
                .flatMap(klass -> Mono.<Void>error(ApiException.unprocessableEntity(
                    "The class belongs to another institution than the statement")));
        Mono<Void> courseOk = courseId == null ? Mono.empty()
            : Mono.defer(() -> courses.findByIdAndDeletedAtIsNull(courseId))
                .filter(course -> !institutionId.equals(course.getInstitutionId()))
                .flatMap(course -> Mono.<Void>error(ApiException.unprocessableEntity(
                    "The course belongs to another institution than the statement")));
        return classOk.then(courseOk);
    }

    private Mono<Void> mustBeInTheCurriculum(Long classId, Long subjectId) {
        if (classId == null || subjectId == null) {
            return Mono.empty();
        }
        return Mono.defer(() -> classes.findByIdAndDeletedAtIsNull(classId))
            .flatMap(klass -> curriculum.requireInClassCurriculum(klass, subjectId));
    }

    /**
     * A teacher may only build for a class they teach, and the teaching
     * assignment is keyed on the class. Naming no class would silently skip that
     * check, so a statement from a teacher has to name one. An administrator is
     * not restricted and may build a school-wide statement.
     *
     * <p>Lives here so that writing and editing read the same way: the rule once
     * reached only the creation path, and a teacher could then edit a school-wide
     * statement that only an administrator was allowed to create.
     */
    public Mono<Void> requireAClassForTeachers(Long classId, boolean admin) {
        if (admin || classId != null) {
            return Mono.empty();
        }
        return Mono.error(ApiException.unprocessableEntity(
            "A teacher must choose the class they teach; "
                + "only an administrator may build a statement without a class"));
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
