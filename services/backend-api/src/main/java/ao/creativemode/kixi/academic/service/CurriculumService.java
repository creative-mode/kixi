package ao.creativemode.kixi.academic.service;

import ao.creativemode.kixi.academic.dto.coursesubject.CourseSubjectRequest;
import ao.creativemode.kixi.academic.dto.coursesubject.CourseSubjectResponse;
import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.model.Course;
import ao.creativemode.kixi.academic.model.CourseSubject;
import ao.creativemode.kixi.academic.model.Subject;
import ao.creativemode.kixi.academic.repository.CourseRepository;
import ao.creativemode.kixi.academic.repository.CourseSubjectRepository;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.AcademicDependencyReader;
import ao.creativemode.kixi.shared.service.InstitutionSubjectCatalog;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The curriculum of a course: which subjects it has, for which grades (issue #253).
 *
 * <p>This is what makes the class a place with subjects. A class has the subjects of its course
 * that apply to its grade; who teaches each one is a teaching assignment. The school's own catalog
 * ({@code institution_subjects}) contains the curriculum, so adding a subject to a course makes sure
 * the school has it.</p>
 */
@Service
public class CurriculumService {

    private final CourseSubjectRepository links;
    private final CourseRepository courses;
    private final SubjectRepository subjects;
    private final InstitutionSubjectCatalog catalog;
    private final AcademicDependencyReader dependencies;

    public CurriculumService(
            CourseSubjectRepository links,
            CourseRepository courses,
            SubjectRepository subjects,
            InstitutionSubjectCatalog catalog,
            AcademicDependencyReader dependencies) {
        this.links = links;
        this.courses = courses;
        this.subjects = subjects;
        this.catalog = catalog;
        this.dependencies = dependencies;
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    public Flux<CourseSubjectResponse> listForCourse(Long courseId) {
        return requireActiveCourse(courseId)
                .thenMany(Flux.defer(() -> links.findAllByCourseIdAndDeletedAtIsNull(courseId)))
                .collectList()
                .flatMapMany(this::withSubjects);
    }

    /** The subjects a class has: those of its course that apply to its grade, in subject order. */
    public Mono<List<Subject>> subjectsOfClass(Class klass) {
        return links.findApplicable(klass.getCourseId(), klass.getGrade())
                .map(CourseSubject::getSubjectId)
                .distinct()
                .collectList()
                .flatMap(ids -> ids.isEmpty()
                        ? Mono.just(List.<Subject>of())
                        : subjects.findAllById(ids)
                                .filter(subject -> subject.getDeletedAt() == null)
                                .collectSortedList(Comparator.comparing(Subject::getName,
                                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))));
    }

    /** Whether the class has the subject, through its course and its grade. */
    public Mono<Boolean> classHasSubject(Class klass, Long subjectId) {
        return links.findApplicable(klass.getCourseId(), klass.getGrade())
                .any(link -> link.getSubjectId().equals(subjectId));
    }

    /** 422 unless the subject is in the curriculum that applies to the class. */
    public Mono<Void> requireInClassCurriculum(Class klass, Long subjectId) {
        return classHasSubject(klass, subjectId)
                .flatMap(has -> has
                        ? Mono.<Void>empty()
                        : Mono.error(ApiException.unprocessableEntity(
                                "The subject is not in the curriculum of this class: add it to its course"
                                        + (klass.getGrade() == null ? "" : " for grade " + klass.getGrade())
                                        + " first")));
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    public Mono<CourseSubjectResponse> add(Long courseId, CourseSubjectRequest data) {
        return requireActiveCourse(courseId)
                .flatMap(course -> subjects.findByIdAndDeletedAtIsNull(data.subjectId())
                        .switchIfEmpty(Mono.error(ApiException.unprocessableEntity(
                                "The subject does not exist or was removed")))
                        .flatMap(subject -> requireNoOverlap(courseId, data)
                                // The curriculum is contained in the school's catalog.
                                .then(Mono.defer(() -> catalog.ensureInCatalog(course.getInstitutionId(), subject.getId())))
                                .then(Mono.defer(() -> save(courseId, data)))
                                .map(saved -> toResponse(saved, subject))))
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> ApiException.conflict("This subject is already in the course for that grade"));
    }

    public Mono<Void> remove(Long courseId, Long linkId) {
        return links.findByIdAndCourseIdAndDeletedAtIsNull(linkId, courseId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Curriculum entry not found")))
                .flatMap(link -> dependencies
                        .assignmentsDependingOnCurriculum(courseId, link.getSubjectId(), link.getGrade(), link.getId())
                        .flatMap(count -> count > 0
                                ? Mono.<CourseSubject>error(ApiException.conflict(
                                        count + " active teaching assignment(s) depend on this subject in the course: "
                                                + "remove or move them first"))
                                : Mono.just(link)))
                .flatMap(link -> {
                    link.setDeletedAt(LocalDateTime.now());
                    return links.save(link);
                })
                .then();
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private Mono<Course> requireActiveCourse(Long courseId) {
        return courses.findByIdAndDeletedAtIsNull(courseId)
                .switchIfEmpty(Mono.error(ApiException.unprocessableEntity("The course does not exist or was removed")));
    }

    /**
     * "All grades" and a specific grade for the same subject overlap, and the unique index (which
     * only sees equal grades) would let both in. Refuse it with the way out.
     */
    private Mono<Void> requireNoOverlap(Long courseId, CourseSubjectRequest data) {
        return links.findAllByCourseIdAndSubjectIdAndDeletedAtIsNull(courseId, data.subjectId())
                .collectList()
                .flatMap(existing -> {
                    for (CourseSubject link : existing) {
                        boolean same = java.util.Objects.equals(link.getGrade(), data.grade());
                        if (same) {
                            return Mono.<Void>error(ApiException.conflict(
                                    "This subject is already in the course"
                                            + (data.grade() == null ? "" : " for grade " + data.grade())));
                        }
                        if (link.getGrade() == null) {
                            return Mono.<Void>error(ApiException.conflict(
                                    "This subject is already in the course for every grade"));
                        }
                        if (data.grade() == null) {
                            return Mono.<Void>error(ApiException.conflict(
                                    "This subject is already in the course for grade " + link.getGrade()
                                            + ": remove the grade-specific entries before adding it for every grade"));
                        }
                    }
                    return Mono.<Void>empty();
                });
    }

    private Mono<CourseSubject> save(Long courseId, CourseSubjectRequest data) {
        CourseSubject link = new CourseSubject();
        link.setCourseId(courseId);
        link.setSubjectId(data.subjectId());
        link.setGrade(data.grade());
        return links.save(link);
    }

    private Flux<CourseSubjectResponse> withSubjects(Collection<CourseSubject> rows) {
        if (rows.isEmpty()) {
            return Flux.empty();
        }
        List<Long> ids = rows.stream().map(CourseSubject::getSubjectId).distinct().toList();
        return subjects.findAllById(ids).collectMap(Subject::getId, Function.identity())
                .flatMapMany(byId -> Flux.fromIterable(rows.stream()
                        .filter(row -> byId.containsKey(row.getSubjectId()))
                        .sorted(Comparator
                                .comparing((CourseSubject row) -> byId.get(row.getSubjectId()).getName(),
                                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                                .thenComparing(row -> row.getGrade() == null ? 0 : row.getGrade()))
                        .map(row -> toResponse(row, byId.get(row.getSubjectId())))
                        .toList()));
    }

    private static CourseSubjectResponse toResponse(CourseSubject link, Subject subject) {
        return new CourseSubjectResponse(link.getId(), link.getCourseId(), link.getSubjectId(),
                subject.getCode(), subject.getName(), subject.getShortName(), link.getGrade());
    }

    /** For read models that already hold the subjects: the entries of many courses grouped by course. */
    public static Map<Long, List<CourseSubject>> byCourse(Collection<CourseSubject> rows) {
        return rows.stream().collect(Collectors.groupingBy(CourseSubject::getCourseId));
    }
}
