package ao.creativemode.kixi.academic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.academic.dto.coursesubject.CourseSubjectRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The curriculum of a course (issue #253): what a class has, and the rules for changing it. */
class CurriculumServiceTest {

    private static final Long COURSE_ID = 1L;
    private static final Long SCHOOL_ID = 9L;
    private static final Long SUBJECT_ID = 5L;

    private CourseSubjectRepository links;
    private CourseRepository courses;
    private SubjectRepository subjects;
    private InstitutionSubjectCatalog catalog;
    private AcademicDependencyReader dependencies;
    private CurriculumService service;

    @BeforeEach
    void setUp() {
        links = mock(CourseSubjectRepository.class);
        courses = mock(CourseRepository.class);
        subjects = mock(SubjectRepository.class);
        catalog = mock(InstitutionSubjectCatalog.class);
        dependencies = mock(AcademicDependencyReader.class);
        service = new CurriculumService(links, courses, subjects, catalog, dependencies);

        when(courses.findByIdAndDeletedAtIsNull(COURSE_ID)).thenReturn(Mono.just(course()));
        when(subjects.findByIdAndDeletedAtIsNull(SUBJECT_ID)).thenReturn(Mono.just(subject()));
        when(links.findAllByCourseIdAndSubjectIdAndDeletedAtIsNull(COURSE_ID, SUBJECT_ID)).thenReturn(Flux.empty());
        when(catalog.ensureInCatalog(SCHOOL_ID, SUBJECT_ID)).thenReturn(Mono.empty());
        when(links.save(any(CourseSubject.class))).thenAnswer(inv -> {
            CourseSubject saved = inv.getArgument(0);
            saved.setId(77L);
            return Mono.just(saved);
        });
    }

    @Test
    void addPutsTheSubjectInTheCourseAndEnsuresItIsInTheCatalogOfTheSchool() {
        StepVerifier.create(service.add(COURSE_ID, new CourseSubjectRequest(SUBJECT_ID, 11)))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(77L);
                    assertThat(response.courseId()).isEqualTo(COURSE_ID);
                    assertThat(response.code()).isEqualTo("MAT");
                    assertThat(response.grade()).isEqualTo(11);
                })
                .verifyComplete();

        verify(catalog).ensureInCatalog(SCHOOL_ID, SUBJECT_ID);
    }

    @Test
    void addForANonexistentSubjectIsUnprocessable() {
        when(subjects.findByIdAndDeletedAtIsNull(SUBJECT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.add(COURSE_ID, new CourseSubjectRequest(SUBJECT_ID, null)))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getStatusCode()).isEqualTo(422))
                .verify();
        verify(links, never()).save(any());
    }

    @Test
    void addForANonexistentCourseIsUnprocessable() {
        when(courses.findByIdAndDeletedAtIsNull(2L)).thenReturn(Mono.empty());

        StepVerifier.create(service.add(2L, new CourseSubjectRequest(SUBJECT_ID, null)))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getStatusCode()).isEqualTo(422))
                .verify();
    }

    @Test
    void addingTheSameEntryTwiceIsAConflict() {
        when(links.findAllByCourseIdAndSubjectIdAndDeletedAtIsNull(COURSE_ID, SUBJECT_ID))
                .thenReturn(Flux.just(link(1L, 11)));

        StepVerifier.create(service.add(COURSE_ID, new CourseSubjectRequest(SUBJECT_ID, 11)))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getStatusCode()).isEqualTo(409))
                .verify();
        verify(links, never()).save(any());
    }

    @Test
    void everyGradeAndASpecificGradeOfTheSameSubjectOverlap() {
        when(links.findAllByCourseIdAndSubjectIdAndDeletedAtIsNull(COURSE_ID, SUBJECT_ID))
                .thenReturn(Flux.just(link(1L, null)));

        StepVerifier.create(service.add(COURSE_ID, new CourseSubjectRequest(SUBJECT_ID, 12)))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getStatusCode()).isEqualTo(409))
                .verify();

        when(links.findAllByCourseIdAndSubjectIdAndDeletedAtIsNull(COURSE_ID, SUBJECT_ID))
                .thenReturn(Flux.just(link(2L, 12)));

        StepVerifier.create(service.add(COURSE_ID, new CourseSubjectRequest(SUBJECT_ID, null)))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getStatusCode()).isEqualTo(409))
                .verify();
    }

    @Test
    void aRaceOnTheUniqueIndexIsReportedAsAConflict() {
        when(links.save(any(CourseSubject.class))).thenReturn(Mono.error(new DataIntegrityViolationException("dup")));

        StepVerifier.create(service.add(COURSE_ID, new CourseSubjectRequest(SUBJECT_ID, 10)))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getStatusCode()).isEqualTo(409))
                .verify();
    }

    @Test
    void removeIsRefusedWhileTeachingAssignmentsDependOnTheEntry() {
        CourseSubject link = link(3L, 11);
        when(links.findByIdAndCourseIdAndDeletedAtIsNull(3L, COURSE_ID)).thenReturn(Mono.just(link));
        when(dependencies.assignmentsDependingOnCurriculum(COURSE_ID, SUBJECT_ID, 11, 3L)).thenReturn(Mono.just(2L));

        StepVerifier.create(service.remove(COURSE_ID, 3L))
                .expectErrorSatisfies(e -> {
                    assertThat(((ApiException) e).getStatusCode()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("2");
                })
                .verify();
        verify(links, never()).save(any());
    }

    @Test
    void removeSoftDeletesTheEntryAndNeverTouchesTheCatalog() {
        CourseSubject link = link(3L, 11);
        when(links.findByIdAndCourseIdAndDeletedAtIsNull(3L, COURSE_ID)).thenReturn(Mono.just(link));
        when(dependencies.assignmentsDependingOnCurriculum(COURSE_ID, SUBJECT_ID, 11, 3L)).thenReturn(Mono.just(0L));

        StepVerifier.create(service.remove(COURSE_ID, 3L)).verifyComplete();

        assertThat(link.getDeletedAt()).isNotNull();
        verify(catalog, never()).ensureInCatalog(any(), any());
    }

    @Test
    void removeOfAnUnknownEntryIsNotFound() {
        when(links.findByIdAndCourseIdAndDeletedAtIsNull(3L, COURSE_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.remove(COURSE_ID, 3L))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getStatusCode()).isEqualTo(404))
                .verify();
    }

    @Test
    void aClassHasTheSubjectsOfItsCourseThatApplyToItsGrade() {
        Class klass = new Class();
        klass.setCourseId(COURSE_ID);
        klass.setGrade(12);
        when(links.findApplicable(COURSE_ID, 12)).thenReturn(Flux.just(link(1L, null)));

        StepVerifier.create(service.classHasSubject(klass, SUBJECT_ID)).expectNext(true).verifyComplete();
        StepVerifier.create(service.classHasSubject(klass, 99L)).expectNext(false).verifyComplete();
    }

    @Test
    void aSubjectOutsideTheCurriculumOfTheClassIsUnprocessable() {
        Class klass = new Class();
        klass.setCourseId(COURSE_ID);
        klass.setGrade(12);
        when(links.findApplicable(COURSE_ID, 12)).thenReturn(Flux.empty());

        StepVerifier.create(service.requireInClassCurriculum(klass, SUBJECT_ID))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getStatusCode()).isEqualTo(422))
                .verify();
    }

    private static Course course() {
        Course course = new Course();
        course.setId(COURSE_ID);
        course.setCode("INF");
        course.setInstitutionId(SCHOOL_ID);
        return course;
    }

    private static Subject subject() {
        Subject subject = new Subject();
        subject.setId(SUBJECT_ID);
        subject.setCode("MAT");
        subject.setName("Matemática");
        return subject;
    }

    private static CourseSubject link(Long id, Integer grade) {
        CourseSubject link = new CourseSubject();
        link.setId(id);
        link.setCourseId(COURSE_ID);
        link.setSubjectId(SUBJECT_ID);
        link.setGrade(grade);
        return link;
    }
}
