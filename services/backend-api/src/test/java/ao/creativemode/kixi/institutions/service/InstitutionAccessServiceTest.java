package ao.creativemode.kixi.institutions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionSubjectRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.institutions.repository.TeachingAssignmentRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class InstitutionAccessServiceTest {

    private InstitutionRepository institutions;
    private InstitutionSubjectRepository subjectLinks;
    private InstitutionTeacherRepository teacherLinks;
    private TeacherRepository teachers;
    private TeachingAssignmentService teachingAssignments;
    private TeachingAssignmentRepository assignmentRepository;
    private ClassRepository classes;
    private SubjectRepository subjects;
    private InstitutionAccessService service;

    @BeforeEach
    void setUp() {
        institutions = mock(InstitutionRepository.class);
        subjectLinks = mock(InstitutionSubjectRepository.class);
        teacherLinks = mock(InstitutionTeacherRepository.class);
        teachers = mock(TeacherRepository.class);
        teachingAssignments = mock(TeachingAssignmentService.class);
        assignmentRepository = mock(TeachingAssignmentRepository.class);
        classes = mock(ClassRepository.class);
        subjects = mock(SubjectRepository.class);
        service = new InstitutionAccessService(
                institutions, subjectLinks, teacherLinks, teachers, teachingAssignments);
    }

    @Test
    void adminMayAuthorWhenInstitutionTeachesTheSubject() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Institution()));
        when(subjectLinks.existsByInstitutionIdAndSubjectIdAndDeletedAtIsNull(1L, 2L)).thenReturn(Mono.just(true));

        StepVerifier.create(service.requireCanAuthor(9L, true, 1L, 2L)).verifyComplete();
    }

    @Test
    void failsWhenInstitutionDoesNotExist() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.requireCanAuthor(9L, true, 1L, 2L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void teacherAffiliatedWithInstitutionMayAuthor() {
        Teacher teacher = new Teacher();
        teacher.setId(5L);
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Institution()));
        when(teachers.findByAccountIdAndDeletedAtIsNull(9L)).thenReturn(Mono.just(teacher));
        when(teacherLinks.existsByInstitutionIdAndTeacherIdAndDeletedAtIsNull(1L, 5L)).thenReturn(Mono.just(true));
        when(subjectLinks.existsByInstitutionIdAndSubjectIdAndDeletedAtIsNull(1L, 2L)).thenReturn(Mono.just(true));

        StepVerifier.create(service.requireCanAuthor(9L, false, 1L, 2L)).verifyComplete();
    }

    @Test
    void teacherNotAffiliatedIsForbidden() {
        Teacher teacher = new Teacher();
        teacher.setId(5L);
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Institution()));
        when(teachers.findByAccountIdAndDeletedAtIsNull(9L)).thenReturn(Mono.just(teacher));
        when(teacherLinks.existsByInstitutionIdAndTeacherIdAndDeletedAtIsNull(1L, 5L)).thenReturn(Mono.just(false));

        StepVerifier.create(service.requireCanAuthor(9L, false, 1L, 2L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
    }

    @Test
    void accountWithoutTeacherProfileIsForbidden() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Institution()));
        when(teachers.findByAccountIdAndDeletedAtIsNull(9L)).thenReturn(Mono.empty());

        StepVerifier.create(service.requireCanAuthor(9L, false, 1L, 2L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
    }

    @Test
    void subjectNotTaughtByInstitutionIsUnprocessable() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Institution()));
        when(subjectLinks.existsByInstitutionIdAndSubjectIdAndDeletedAtIsNull(1L, 2L)).thenReturn(Mono.just(false));

        StepVerifier.create(service.requireCanAuthor(9L, true, 1L, 2L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .verify();
    }

    // â”€â”€ The class-scoped rule: affiliation is not enough â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void affiliatedTeacherAssignedToTheClassMayAuthor() {
        givenAffiliatedTeacher();
        when(teachingAssignments.requireTeaches(9L, false, 3L, 2L)).thenReturn(Mono.empty());

        StepVerifier.create(service.requireCanAuthor(9L, false, 1L, 2L, 3L)).verifyComplete();
    }

    @Test
    void affiliatedTeacherNotAssignedToTheClassIsForbidden() {
        givenAffiliatedTeacher();
        when(teachingAssignments.requireTeaches(9L, false, 3L, 2L))
                .thenReturn(Mono.error(ApiException.forbidden(
                        "Teacher is not assigned to this class and subject")));

        StepVerifier.create(service.requireCanAuthor(9L, false, 1L, 2L, 3L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
    }

    @Test
    void theAssignmentCheckIsToldThatTheAccountIsAnAdministrator() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Institution()));
        when(subjectLinks.existsByInstitutionIdAndSubjectIdAndDeletedAtIsNull(1L, 2L)).thenReturn(Mono.just(true));
        when(teachingAssignments.requireTeaches(9L, true, 3L, 2L)).thenReturn(Mono.empty());

        StepVerifier.create(service.requireCanAuthor(9L, true, 1L, 2L, 3L)).verifyComplete();

        verify(teachingAssignments).requireTeaches(9L, true, 3L, 2L);
    }

    @Test
    void theAssignmentIsNotConsultedWhenTheInstitutionCheckAlreadyFailed() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.requireCanAuthor(9L, false, 1L, 2L, 3L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        org.mockito.Mockito.verifyNoInteractions(teachingAssignments);
    }

    @Test
    void anAdministratorWithoutATeacherProfileIsNotStoppedByTheAssignmentRule() {
        // Wired for real: a mocked requireTeaches would only assert what the
        // test told it to do. An administrator who never taught has no teacher
        // profile, and must still be able to author.
        TeachingAssignmentService realAssignments = new TeachingAssignmentService(
                assignmentRepository, teachers, classes, subjects);
        InstitutionAccessService wired = new InstitutionAccessService(
                institutions, subjectLinks, teacherLinks, teachers, realAssignments);

        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Institution()));
        when(subjectLinks.existsByInstitutionIdAndSubjectIdAndDeletedAtIsNull(1L, 2L))
                .thenReturn(Mono.just(true));
        when(teachers.findByAccountIdAndDeletedAtIsNull(9L)).thenReturn(Mono.empty());

        StepVerifier.create(wired.requireCanAuthor(9L, true, 1L, 2L, 3L)).verifyComplete();
    }

    @Test
    void anAdministratorWithATeacherProfileAndNoAssignmentIsNotStopped() {
        TeachingAssignmentService realAssignments = new TeachingAssignmentService(
                assignmentRepository, teachers, classes, subjects);
        InstitutionAccessService wired = new InstitutionAccessService(
                institutions, subjectLinks, teacherLinks, teachers, realAssignments);

        Teacher teacher = new Teacher();
        teacher.setId(5L);
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Institution()));
        when(subjectLinks.existsByInstitutionIdAndSubjectIdAndDeletedAtIsNull(1L, 2L))
                .thenReturn(Mono.just(true));
        when(teachers.findByAccountIdAndDeletedAtIsNull(9L)).thenReturn(Mono.just(teacher));
        when(assignmentRepository
                .existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                        5L, 3L, 2L, 7L)).thenReturn(Mono.just(false));

        StepVerifier.create(wired.requireCanAuthor(9L, true, 1L, 2L, 3L)).verifyComplete();
    }

    private void givenAffiliatedTeacher() {
        Teacher teacher = new Teacher();
        teacher.setId(5L);
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(new Institution()));
        when(teachers.findByAccountIdAndDeletedAtIsNull(9L)).thenReturn(Mono.just(teacher));
        when(teacherLinks.existsByInstitutionIdAndTeacherIdAndDeletedAtIsNull(1L, 5L)).thenReturn(Mono.just(true));
        when(subjectLinks.existsByInstitutionIdAndSubjectIdAndDeletedAtIsNull(1L, 2L)).thenReturn(Mono.just(true));
    }
}
