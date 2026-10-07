package ao.creativemode.kixi.exams.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.model.Course;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.academic.model.Subject;
import ao.creativemode.kixi.academic.model.Term;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.CourseRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import ao.creativemode.kixi.academic.repository.TermRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class StatementLinkValidationServiceTest {

    private static final Long SCHOOL_YEAR_ID = 1L;
    private static final Long TERM_ID = 2L;
    private static final Long CLASS_ID = 3L;
    private static final Long SUBJECT_ID = 4L;
    private static final Long COURSE_ID = 5L;

    private SchoolYearRepository schoolYears;
    private TermRepository terms;
    private SubjectRepository subjects;
    private CourseRepository courses;
    private ClassRepository classes;
    private StatementLinkValidationService service;

    @BeforeEach
    void setUp() {
        schoolYears = mock(SchoolYearRepository.class);
        terms = mock(TermRepository.class);
        subjects = mock(SubjectRepository.class);
        courses = mock(CourseRepository.class);
        classes = mock(ClassRepository.class);
        service = new StatementLinkValidationService(
                schoolYears, terms, subjects, courses, classes);
    }

    @Test
    void acceptsMetadataThatHangsTogether() {
        givenEveryReferenceExists();

        StepVerifier.create(service.validate(
                        SCHOOL_YEAR_ID, TERM_ID, CLASS_ID, SUBJECT_ID, COURSE_ID))
                .verifyComplete();
    }

    @Test
    void acceptsASchoolStatementWithNothingButASubject() {
        when(subjects.findByIdAndDeletedAtIsNull(SUBJECT_ID))
                .thenReturn(Mono.just(subject(SUBJECT_ID)));

        StepVerifier.create(service.validate(null, null, null, SUBJECT_ID, null)).verifyComplete();

        verifyNoInteractions(schoolYears, terms, courses, classes);
    }

    @Test
    void rejectsAnUnknownSchoolYear() {
        when(schoolYears.findByIdAndDeletedAtIsNull(SCHOOL_YEAR_ID)).thenReturn(Mono.empty());

        expectUnprocessable(SCHOOL_YEAR_ID, null, null, null, null);
    }

    @Test
    void rejectsAnUnknownTerm() {
        when(schoolYears.findByIdAndDeletedAtIsNull(SCHOOL_YEAR_ID))
                .thenReturn(Mono.just(schoolYear(SCHOOL_YEAR_ID)));
        when(terms.findByIdAndDeletedAtIsNull(TERM_ID)).thenReturn(Mono.empty());

        expectUnprocessable(SCHOOL_YEAR_ID, TERM_ID, null, null, null);
    }

    @Test
    void rejectsAnUnknownSubject() {
        when(subjects.findByIdAndDeletedAtIsNull(SUBJECT_ID)).thenReturn(Mono.empty());

        expectUnprocessable(null, null, null, SUBJECT_ID, null);
    }

    @Test
    void rejectsAnUnknownCourse() {
        when(courses.findByIdAndDeletedAtIsNull(COURSE_ID)).thenReturn(Mono.empty());

        expectUnprocessable(null, null, null, null, COURSE_ID);
    }

    @Test
    void rejectsAnUnknownClass() {
        when(classes.findByIdAndDeletedAtIsNull(CLASS_ID)).thenReturn(Mono.empty());

        expectUnprocessable(null, null, CLASS_ID, null, null);
    }

    @Test
    void rejectsASchoolYearThatIsNotTheOneOfTheClass() {
        when(schoolYears.findByIdAndDeletedAtIsNull(SCHOOL_YEAR_ID))
                .thenReturn(Mono.just(schoolYear(SCHOOL_YEAR_ID)));
        when(classes.findByIdAndDeletedAtIsNull(CLASS_ID))
                .thenReturn(Mono.just(klass(CLASS_ID, SCHOOL_YEAR_ID + 1, COURSE_ID)));

        expectUnprocessable(SCHOOL_YEAR_ID, null, CLASS_ID, null, null);
    }

    @Test
    void rejectsACourseThatIsNotTheOneOfTheClass() {
        when(courses.findByIdAndDeletedAtIsNull(COURSE_ID)).thenReturn(Mono.just(course(COURSE_ID)));
        when(classes.findByIdAndDeletedAtIsNull(CLASS_ID))
                .thenReturn(Mono.just(klass(CLASS_ID, SCHOOL_YEAR_ID, COURSE_ID + 1)));

        expectUnprocessable(null, null, CLASS_ID, null, COURSE_ID);
    }

    @Test
    void acceptsAClassOnItsOwnWithoutNamingItsYearOrCourse() {
        when(classes.findByIdAndDeletedAtIsNull(CLASS_ID))
                .thenReturn(Mono.just(klass(CLASS_ID, SCHOOL_YEAR_ID, COURSE_ID)));

        StepVerifier.create(service.validate(null, null, CLASS_ID, null, null)).verifyComplete();
    }

    // ── requireAClassForTeachers: the rule that reached only the create path ──

    @Test
    void aTeacherMustNameAClass() {
        expectClassRequiredUnprocessable(null, false);
    }

    @Test
    void aTeacherMayNameAClass() {
        StepVerifier.create(service.requireAClassForTeachers(CLASS_ID, false)).verifyComplete();
    }

    @Test
    void anAdministratorMayNameNoClass() {
        StepVerifier.create(service.requireAClassForTeachers(null, true)).verifyComplete();
    }

    @Test
    void aTeacherNamedAClassThatDoesNotExistStillFailsLaterOn() {
        // This rule only asks whether a class was named; whether it exists is
        // validate()'s business, so nothing here touches the repositories.
        StepVerifier.create(service.requireAClassForTeachers(999L, false)).verifyComplete();

        verifyNoInteractions(schoolYears, terms, subjects, courses, classes);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private void givenEveryReferenceExists() {
        when(schoolYears.findByIdAndDeletedAtIsNull(SCHOOL_YEAR_ID))
                .thenReturn(Mono.just(schoolYear(SCHOOL_YEAR_ID)));
        when(terms.findByIdAndDeletedAtIsNull(TERM_ID)).thenReturn(Mono.just(term(TERM_ID)));
        when(subjects.findByIdAndDeletedAtIsNull(SUBJECT_ID))
                .thenReturn(Mono.just(subject(SUBJECT_ID)));
        when(courses.findByIdAndDeletedAtIsNull(COURSE_ID)).thenReturn(Mono.just(course(COURSE_ID)));
        when(classes.findByIdAndDeletedAtIsNull(CLASS_ID))
                .thenReturn(Mono.just(klass(CLASS_ID, SCHOOL_YEAR_ID, COURSE_ID)));
    }

    private void expectClassRequiredUnprocessable(Long classId, boolean admin) {
        StepVerifier.create(service.requireAClassForTeachers(classId, admin))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatus())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getMessage()).contains("must choose the class");
                })
                .verify();
    }

    private void expectUnprocessable(        Long schoolYearId,
        Long termId,
        Long classId,
        Long subjectId,
        Long courseId
    ) {
        StepVerifier.create(service.validate(schoolYearId, termId, classId, subjectId, courseId))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .verify();
    }

    private SchoolYear schoolYear(Long id) {
        SchoolYear year = new SchoolYear();
        year.setId(id);
        return year;
    }

    private Term term(Long id) {
        Term term = new Term();
        term.setId(id);
        return term;
    }

    private Subject subject(Long id) {
        Subject subject = new Subject();
        subject.setId(id);
        return subject;
    }

    private Course course(Long id) {
        Course course = new Course();
        course.setId(id);
        return course;
    }

    private Class klass(Long id, Long schoolYearId, Long courseId) {
        Class klass = new Class();
        klass.setId(id);
        klass.setGrade(12);
        klass.setSchoolYearId(schoolYearId);
        klass.setCourseId(courseId);
        return klass;
    }
}
