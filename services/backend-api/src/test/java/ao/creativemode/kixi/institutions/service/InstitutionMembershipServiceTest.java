package ao.creativemode.kixi.institutions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.academic.model.Subject;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.model.User;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.identity.repository.UserRepository;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.model.InstitutionStudent;
import ao.creativemode.kixi.institutions.model.InstitutionSubject;
import ao.creativemode.kixi.institutions.model.InstitutionTeacher;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionStudentRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionSubjectRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class InstitutionMembershipServiceTest {

    private InstitutionRepository institutions;
    private InstitutionSubjectRepository subjectLinks;
    private InstitutionTeacherRepository teacherLinks;
    private InstitutionStudentRepository studentLinks;
    private SubjectRepository subjects;
    private TeacherRepository teachers;
    private UserRepository users;
    private InstitutionMembershipService service;

    @BeforeEach
    void setUp() {
        institutions = mock(InstitutionRepository.class);
        subjectLinks = mock(InstitutionSubjectRepository.class);
        teacherLinks = mock(InstitutionTeacherRepository.class);
        studentLinks = mock(InstitutionStudentRepository.class);
        subjects = mock(SubjectRepository.class);
        teachers = mock(TeacherRepository.class);
        users = mock(UserRepository.class);
        service = new InstitutionMembershipService(
                institutions, subjectLinks, teacherLinks, studentLinks, subjects, teachers, users);
    }

    // ── Subjects ────────────────────────────────────────────────────────────

    @Test
    void addSubjectCreatesTheLinkWhenNoneExists() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(institution(1L)));
        when(subjects.findById(2L)).thenReturn(Mono.just(subject(2L)));
        when(subjectLinks.findFirstByInstitutionIdAndSubjectId(1L, 2L)).thenReturn(Mono.empty());
        when(subjectLinks.save(any(InstitutionSubject.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.addSubject(1L, 2L)).verifyComplete();

        ArgumentCaptor<InstitutionSubject> saved = ArgumentCaptor.forClass(InstitutionSubject.class);
        verify(subjectLinks).save(saved.capture());
        assertThat(saved.getValue().getInstitutionId()).isEqualTo(1L);
        assertThat(saved.getValue().getSubjectId()).isEqualTo(2L);
    }

    @Test
    void addSubjectRestoresATrashedLinkInsteadOfDuplicatingIt() {
        InstitutionSubject trashed = new InstitutionSubject(1L, 2L);
        trashed.markAsDeleted();
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(institution(1L)));
        when(subjects.findById(2L)).thenReturn(Mono.just(subject(2L)));
        when(subjectLinks.findFirstByInstitutionIdAndSubjectId(1L, 2L)).thenReturn(Mono.just(trashed));
        when(subjectLinks.save(trashed)).thenReturn(Mono.just(trashed));

        StepVerifier.create(service.addSubject(1L, 2L)).verifyComplete();

        assertThat(trashed.isDeleted()).isFalse();
        verify(subjectLinks).save(trashed);
    }

    @Test
    void addSubjectConflictsWhenTheLinkIsAlreadyActive() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(institution(1L)));
        when(subjects.findById(2L)).thenReturn(Mono.just(subject(2L)));
        when(subjectLinks.findFirstByInstitutionIdAndSubjectId(1L, 2L))
                .thenReturn(Mono.just(new InstitutionSubject(1L, 2L)));

        StepVerifier.create(service.addSubject(1L, 2L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();

        verify(subjectLinks, never()).save(any(InstitutionSubject.class));
    }

    @Test
    void addSubjectFailsForAnUnknownSubject() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(institution(1L)));
        when(subjects.findById(2L)).thenReturn(Mono.empty());

        StepVerifier.create(service.addSubject(1L, 2L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verifyNoInteractions(subjectLinks);
    }

    @Test
    void addSubjectFailsForAnUnknownInstitutionWithoutLookingUpTheSubject() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.addSubject(1L, 2L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verifyNoInteractions(subjects, subjectLinks);
    }

    @Test
    void removeSubjectSoftDeletesTheLink() {
        InstitutionSubject link = new InstitutionSubject(1L, 2L);
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(institution(1L)));
        when(subjectLinks.findFirstByInstitutionIdAndSubjectId(1L, 2L)).thenReturn(Mono.just(link));
        when(subjectLinks.save(link)).thenReturn(Mono.just(link));

        StepVerifier.create(service.removeSubject(1L, 2L)).verifyComplete();

        assertThat(link.isDeleted()).isTrue();
    }

    @Test
    void removeSubjectFailsWhenThereIsNoActiveLink() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(institution(1L)));
        when(subjectLinks.findFirstByInstitutionIdAndSubjectId(1L, 2L)).thenReturn(Mono.empty());

        StepVerifier.create(service.removeSubject(1L, 2L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    // ── Teachers and students ───────────────────────────────────────────────

    @Test
    void addTeacherCreatesTheAffiliation() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(institution(1L)));
        when(teachers.findByIdAndDeletedAtIsNull(5L)).thenReturn(Mono.just(teacher(5L)));
        when(teacherLinks.findFirstByInstitutionIdAndTeacherId(1L, 5L)).thenReturn(Mono.empty());
        when(teacherLinks.save(any(InstitutionTeacher.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.addTeacher(1L, 5L)).verifyComplete();

        ArgumentCaptor<InstitutionTeacher> saved = ArgumentCaptor.forClass(InstitutionTeacher.class);
        verify(teacherLinks).save(saved.capture());
        assertThat(saved.getValue().getInstitutionId()).isEqualTo(1L);
        assertThat(saved.getValue().getTeacherId()).isEqualTo(5L);
    }

    @Test
    void addStudentRestoresATrashedEnrolment() {
        InstitutionStudent trashed = new InstitutionStudent(1L, 6L);
        trashed.markAsDeleted();
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(institution(1L)));
        when(users.findByIdAndDeletedAtIsNull(6L)).thenReturn(Mono.just(user(6L)));
        when(studentLinks.findFirstByInstitutionIdAndUserId(1L, 6L)).thenReturn(Mono.just(trashed));
        when(studentLinks.save(trashed)).thenReturn(Mono.just(trashed));

        StepVerifier.create(service.addStudent(1L, 6L)).verifyComplete();

        assertThat(trashed.isDeleted()).isFalse();
    }

    // ── What the signed-in account belongs to ───────────────────────────────

    @Test
    void findForAccountReturnsEveryActiveInstitutionToAnAdministrator() {
        when(institutions.findAllByDeletedAtIsNull())
                .thenReturn(Flux.just(institution(1L), institution(2L)));

        StepVerifier.create(service.findForAccount(9L, true).collectList())
                .assertNext(list -> assertThat(list).hasSize(2))
                .verifyComplete();

        verifyNoInteractions(teachers, users);
    }

    @Test
    void findForAccountMergesTeacherAndStudentAffiliationsWithoutDuplicates() {
        when(teachers.findByAccountIdAndDeletedAtIsNull(9L)).thenReturn(Mono.just(teacher(5L)));
        when(teacherLinks.findAllByTeacherIdAndDeletedAtIsNull(5L))
                .thenReturn(Flux.just(new InstitutionTeacher(1L, 5L)));
        when(users.findByAccountIdAndDeletedAtIsNull(9L)).thenReturn(Flux.just(user(6L)));
        when(studentLinks.findAllByUserIdAndDeletedAtIsNull(6L))
                .thenReturn(Flux.just(new InstitutionStudent(1L, 6L), new InstitutionStudent(2L, 6L)));
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(institution(1L)));
        when(institutions.findByIdAndDeletedAtIsNull(2L)).thenReturn(Mono.just(institution(2L)));

        StepVerifier.create(service.findForAccount(9L, false).map(response -> response.id()).collectList())
                .assertNext(ids -> assertThat(ids).containsExactlyInAnyOrderElementsOf(List.of(1L, 2L)))
                .verifyComplete();
    }

    private Institution institution(Long id) {
        Institution institution = new Institution();
        institution.setId(id);
        institution.setCode("INST-" + id);
        institution.setName("Institution " + id);
        return institution;
    }

    private Subject subject(Long id) {
        Subject subject = new Subject();
        subject.setId(id);
        subject.setCode("MAT");
        subject.setName("Matemática");
        return subject;
    }

    private Teacher teacher(Long id) {
        Teacher teacher = new Teacher();
        teacher.setId(id);
        teacher.setFirstName("Ana");
        teacher.setLastName("Silva");
        return teacher;
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setAccountId(9L);
        user.setFirstName("João");
        user.setLastName("Pedro");
        return user;
    }
}
