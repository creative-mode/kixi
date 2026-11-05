package ao.creativemode.kixi.institutions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import ao.creativemode.kixi.institutions.repository.AcademicDependencyQueries;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionStudentRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionSubjectRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.institutions.dto.membership.StudentLinkResponse;
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
    private AcademicDependencyQueries dependencies;
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
        dependencies = mock(AcademicDependencyQueries.class);
        when(dependencies.curriculumEntriesOfSchoolSubject(any(), any())).thenReturn(Mono.just(0L));
        service = new InstitutionMembershipService(
                institutions, subjectLinks, teacherLinks, studentLinks, subjects, teachers, users, dependencies);
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
    void removeSubjectIsRefusedWhileACourseCurriculumUsesIt() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(institution(1L)));
        InstitutionSubject link = new InstitutionSubject(1L, 2L);
        when(subjectLinks.findFirstByInstitutionIdAndSubjectId(1L, 2L)).thenReturn(Mono.just(link));
        when(dependencies.curriculumEntriesOfSchoolSubject(1L, 2L)).thenReturn(Mono.just(2L));

        StepVerifier.create(service.removeSubject(1L, 2L))
                .expectErrorSatisfies(e -> assertThat(((ao.creativemode.kixi.shared.exception.ApiException) e)
                        .getStatus().value()).isEqualTo(409))
                .verify();
        verify(subjectLinks, org.mockito.Mockito.never()).save(any());
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

    // ── Número de processo (kixi#512) ───────────────────────────────────────

    @Test
    void theProcessNumberIsTrimmedBlankMeansNoneAndItNeverExceedsTheEngineLimit() {
        assertThat(InstitutionMembershipService.normalizeProcessNumber("  P-2026/0042 ")).isEqualTo("P-2026/0042");
        assertThat(InstitutionMembershipService.normalizeProcessNumber("   ")).isNull();
        assertThat(InstitutionMembershipService.normalizeProcessNumber(null)).isNull();
        assertThat(InstitutionMembershipService.normalizeProcessNumber("x".repeat(30))).hasSize(30);
        for (String bad : List.of("x".repeat(31), "<script>", "ab\ncd", "-começa-com-traço", "a;b", "a\u0000b")) {
            assertThatThrownBy(() -> InstitutionMembershipService.normalizeProcessNumber(bad))
                    .as(bad).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatusCode()).isEqualTo(400));
        }
    }

    @Test
    void setProcessNumberCreatesTheLinkWhenTheStudentHasNoneYet() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(institution(1L)));
        when(users.findByIdAndDeletedAtIsNull(6L)).thenReturn(Mono.just(user(6L)));
        when(studentLinks.existsByInstitutionIdAndProcessNumberAndUserIdNotAndDeletedAtIsNull(1L, "42", 6L)).thenReturn(Mono.just(false));
        when(studentLinks.findFirstByInstitutionIdAndUserId(1L, 6L)).thenReturn(Mono.empty());
        when(studentLinks.save(any(InstitutionStudent.class))).thenAnswer(i -> Mono.just(i.getArgument(0)));

        StepVerifier.create(service.setProcessNumber(1L, 6L, " 42 "))
                .assertNext(link -> assertThat(link.processNumber()).isEqualTo("42"))
                .verifyComplete();

        ArgumentCaptor<InstitutionStudent> saved = ArgumentCaptor.forClass(InstitutionStudent.class);
        verify(studentLinks).save(saved.capture());
        assertThat(saved.getValue().getInstitutionId()).isEqualTo(1L);
        assertThat(saved.getValue().getProcessNumber()).isEqualTo("42");
    }

    @Test
    void setProcessNumberIsAConflictWhenAnotherStudentOfTheSchoolHasIt() {
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(institution(1L)));
        when(users.findByIdAndDeletedAtIsNull(6L)).thenReturn(Mono.just(user(6L)));
        when(studentLinks.existsByInstitutionIdAndProcessNumberAndUserIdNotAndDeletedAtIsNull(1L, "42", 6L)).thenReturn(Mono.just(true));

        StepVerifier.create(service.setProcessNumber(1L, 6L, "42"))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getStatusCode()).isEqualTo(409))
                .verify();
        verify(studentLinks, never()).save(any());
    }

    @Test
    void aBlankProcessNumberClearsItWithoutAskingWhetherItIsTaken() {
        InstitutionStudent link = new InstitutionStudent(1L, 6L);
        link.setProcessNumber("42");
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(institution(1L)));
        when(users.findByIdAndDeletedAtIsNull(6L)).thenReturn(Mono.just(user(6L)));
        when(studentLinks.findFirstByInstitutionIdAndUserId(1L, 6L)).thenReturn(Mono.just(link));
        when(studentLinks.save(link)).thenReturn(Mono.just(link));

        StepVerifier.create(service.setProcessNumber(1L, 6L, "  "))
                .assertNext(result -> assertThat(result.processNumber()).isNull())
                .verifyComplete();
        assertThat(link.getProcessNumber()).isNull();
        verify(studentLinks, never()).existsByInstitutionIdAndProcessNumberAndUserIdNotAndDeletedAtIsNull(any(), any(), any());
    }

    @Test
    void restoringALinkWhoseNumberWasTakenMeanwhileBringsItBackWithoutTheNumber() {
        InstitutionStudent trashed = new InstitutionStudent(1L, 6L);
        trashed.setProcessNumber("42");
        trashed.markAsDeleted();
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(institution(1L)));
        when(users.findByIdAndDeletedAtIsNull(6L)).thenReturn(Mono.just(user(6L)));
        when(studentLinks.findFirstByInstitutionIdAndUserId(1L, 6L)).thenReturn(Mono.just(trashed));
        when(studentLinks.existsByInstitutionIdAndProcessNumberAndUserIdNotAndDeletedAtIsNull(1L, "42", 6L)).thenReturn(Mono.just(true));
        when(studentLinks.save(trashed)).thenReturn(Mono.just(trashed));

        StepVerifier.create(service.addStudent(1L, 6L)).verifyComplete();

        assertThat(trashed.isDeleted()).isFalse();
        assertThat(trashed.getProcessNumber()).as("o número é agora de outro aluno").isNull();
    }

    @Test
    void findStudentsCarriesTheProcessNumberOfEachStudent() {
        InstitutionStudent with = new InstitutionStudent(1L, 6L);
        with.setProcessNumber("42");
        InstitutionStudent without = new InstitutionStudent(1L, 7L);
        when(institutions.findByIdAndDeletedAtIsNull(1L)).thenReturn(Mono.just(institution(1L)));
        when(studentLinks.findAllByInstitutionIdAndDeletedAtIsNull(1L)).thenReturn(Flux.just(with, without));
        when(users.findAllById(any(Iterable.class))).thenReturn(Flux.just(user(6L), user(7L)));

        StepVerifier.create(service.findStudents(1L).collectList())
                .assertNext(list -> assertThat(list).extracting(StudentLinkResponse::userId, StudentLinkResponse::processNumber)
                        .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(6L, "42"), org.assertj.core.groups.Tuple.tuple(7L, null)))
                .verifyComplete();
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
