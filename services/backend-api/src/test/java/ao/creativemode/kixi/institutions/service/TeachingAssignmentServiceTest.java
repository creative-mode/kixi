package ao.creativemode.kixi.institutions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.model.Subject;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.SubjectRepository;
import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.institutions.dto.assignment.TeachingAssignmentRequest;
import ao.creativemode.kixi.institutions.model.TeachingAssignment;
import ao.creativemode.kixi.institutions.repository.TeachingAssignmentRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TeachingAssignmentServiceTest {

    private static final Long TEACHER_ID = 7L;
    private static final Long ACCOUNT_ID = 42L;
    private static final Long CLASS_ID = 3L;
    private static final Long SUBJECT_ID = 5L;
    private static final Long SCHOOL_YEAR_ID = 2024L;

    private TeachingAssignmentRepository assignments;
    private TeacherRepository teacherRepository;
    private ClassRepository classRepository;
    private SubjectRepository subjectRepository;
    private TeachingAssignmentService service;

    @BeforeEach
    void setUp() {
        assignments = mock(TeachingAssignmentRepository.class);
        teacherRepository = mock(TeacherRepository.class);
        classRepository = mock(ClassRepository.class);
        subjectRepository = mock(SubjectRepository.class);
        service = new TeachingAssignmentService(
                assignments, teacherRepository, classRepository, subjectRepository);
    }

    // ── Create ──────────────────────────────────────────────────────────────

    @Test
    void createSavesTheAssignmentAndTrimsTheTutorStyle() {
        givenReferencesExist();
        when(assignments.findFirstByTeacherIdAndClassIdAndSubjectIdAndSchoolYearId(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.empty());
        when(assignments.save(any(TeachingAssignment.class)))
                .thenAnswer(invocation -> Mono.just(withId(invocation.getArgument(0), 11L)));

        StepVerifier.create(service.create(request(" Professor titular ")))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(11L);
                    assertThat(response.tutorStyle()).isEqualTo("Professor titular");
                })
                .verifyComplete();

        ArgumentCaptor<TeachingAssignment> saved = ArgumentCaptor.forClass(TeachingAssignment.class);
        verify(assignments).save(saved.capture());
        assertThat(saved.getValue().getTeacherId()).isEqualTo(TEACHER_ID);
        assertThat(saved.getValue().getClassId()).isEqualTo(CLASS_ID);
        assertThat(saved.getValue().getSubjectId()).isEqualTo(SUBJECT_ID);
        assertThat(saved.getValue().getSchoolYearId()).isEqualTo(SCHOOL_YEAR_ID);
    }

    @Test
    void createDropsABlankTutorStyle() {
        givenReferencesExist();
        when(assignments.findFirstByTeacherIdAndClassIdAndSubjectIdAndSchoolYearId(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.empty());
        when(assignments.save(any(TeachingAssignment.class)))
                .thenAnswer(invocation -> Mono.just(withId(invocation.getArgument(0), 11L)));

        StepVerifier.create(service.create(request("   ")))
                .assertNext(response -> assertThat(response.tutorStyle()).isNull())
                .verifyComplete();
    }

    @Test
    void createRestoresATrashedAssignmentInsteadOfDuplicatingIt() {
        givenReferencesExist();
        TeachingAssignment trashed = assignment(9L, LocalDateTime.now());
        when(assignments.findFirstByTeacherIdAndClassIdAndSubjectIdAndSchoolYearId(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.just(trashed));
        when(assignments.save(trashed)).thenReturn(Mono.just(trashed));

        StepVerifier.create(service.create(request("Professor titular")))
                .assertNext(response -> assertThat(response.tutorStyle()).isEqualTo("Professor titular"))
                .verifyComplete();

        assertThat(trashed.isDeleted()).isFalse();
        assertThat(trashed.getTutorStyle()).isEqualTo("Professor titular");
        verify(assignments).save(trashed);
    }

    @Test
    void createRejectsAnAssignmentThatIsAlreadyActive() {
        givenReferencesExist();
        when(assignments.findFirstByTeacherIdAndClassIdAndSubjectIdAndSchoolYearId(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID))
                .thenReturn(Mono.just(assignment(9L, null)));

        StepVerifier.create(service.create(request(null)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();

        verify(assignments, never()).save(any(TeachingAssignment.class));
    }

    @Test
    void createMapsTheUniqueConstraintToConflict() {
        givenReferencesExist();
        when(assignments.findFirstByTeacherIdAndClassIdAndSubjectIdAndSchoolYearId(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.empty());
        when(assignments.save(any(TeachingAssignment.class)))
                .thenReturn(Mono.error(new DataIntegrityViolationException("uq_teaching_assignment")));

        StepVerifier.create(service.create(request(null)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();
    }

    @Test
    void createRejectsAnUnknownTeacher() {
        when(teacherRepository.findByIdAndDeletedAtIsNull(TEACHER_ID)).thenReturn(Mono.empty());
        givenClassAndSubjectExist();

        StepVerifier.create(service.create(request(null)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verifyNoInteractions(assignments);
    }

    @Test
    void createRejectsAnUnknownClass() {
        when(teacherRepository.findByIdAndDeletedAtIsNull(TEACHER_ID))
                .thenReturn(Mono.just(teacher(TEACHER_ID, ACCOUNT_ID)));
        when(classRepository.findByIdAndDeletedAtIsNull(CLASS_ID)).thenReturn(Mono.empty());
        givenSubjectExists();

        StepVerifier.create(service.create(request(null)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verifyNoInteractions(assignments);
    }

    @Test
    void createRejectsAnUnknownSubject() {
        when(teacherRepository.findByIdAndDeletedAtIsNull(TEACHER_ID))
                .thenReturn(Mono.just(teacher(TEACHER_ID, ACCOUNT_ID)));
        givenClassExists();
        when(subjectRepository.findByIdAndDeletedAtIsNull(SUBJECT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.create(request(null)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verifyNoInteractions(assignments);
    }

    @Test
    void createRejectsASchoolYearThatIsNotTheOneOfTheClass() {
        givenReferencesExist();

        StepVerifier.create(service.create(new TeachingAssignmentRequest(
                        TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID + 1, null)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .verify();

        verifyNoInteractions(assignments);
    }

    // ── Update ──────────────────────────────────────────────────────────────

    @Test
    void updateReplacesTheFields() {
        givenReferencesExist();
        TeachingAssignment existing = assignment(9L, null);
        when(assignments.findByIdAndDeletedAtIsNull(9L)).thenReturn(Mono.just(existing));
        when(assignments.save(existing)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.update(9L, request("Professor adjunto")))
                .assertNext(response -> assertThat(response.tutorStyle()).isEqualTo("Professor adjunto"))
                .verifyComplete();
    }

    @Test
    void updateOfAnUnknownAssignmentIsNotFound() {
        givenReferencesExist();
        when(assignments.findByIdAndDeletedAtIsNull(9L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(9L, request(null)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verify(assignments, never()).save(any(TeachingAssignment.class));
    }

    @Test
    void updateOntoATupleHeldByAnotherAssignmentIsReportedAsConflict() {
        // The constraint covers the four columns and not deleted_at, so a tuple
        // held by a trashed row cannot also be taken by the row being updated:
        // restoring one and moving the other would put two rows on it. The
        // caller is told how to clear it instead.
        givenReferencesExist();
        TeachingAssignment existing = assignment(9L, null);
        when(assignments.findByIdAndDeletedAtIsNull(9L)).thenReturn(Mono.just(existing));
        when(assignments.save(existing))
                .thenReturn(Mono.error(new DataIntegrityViolationException("uq_teaching_assignment")));

        StepVerifier.create(service.update(9L, request(null)))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getMessage()).contains("restore or purge");
                })
                .verify();
    }

    @Test
    void updateMapsTheUniqueConstraintToConflict() {
        givenReferencesExist();
        TeachingAssignment existing = assignment(9L, null);
        when(assignments.findByIdAndDeletedAtIsNull(9L)).thenReturn(Mono.just(existing));
        when(assignments.save(existing))
                .thenReturn(Mono.error(new DataIntegrityViolationException("uq_teaching_assignment")));

        StepVerifier.create(service.update(9L, request(null)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();
    }

    // ── Delete, restore, purge ──────────────────────────────────────────────

    @Test
    void softDeleteMarksTheAssignmentAsDeleted() {
        TeachingAssignment existing = assignment(9L, null);
        when(assignments.findByIdAndDeletedAtIsNull(9L)).thenReturn(Mono.just(existing));
        when(assignments.save(existing)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.softDelete(9L)).verifyComplete();

        assertThat(existing.isDeleted()).isTrue();
    }

    @Test
    void softDeleteOfAnUnknownAssignmentIsNotFound() {
        when(assignments.findByIdAndDeletedAtIsNull(9L)).thenReturn(Mono.empty());

        StepVerifier.create(service.softDelete(9L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void restoreBringsBackATrashedAssignment() {
        TeachingAssignment trashed = assignment(9L, LocalDateTime.now());
        when(assignments.findByIdAndDeletedAtIsNotNull(9L)).thenReturn(Mono.just(trashed));
        when(assignments.save(trashed)).thenReturn(Mono.just(trashed));

        StepVerifier.create(service.restore(9L)).verifyComplete();

        assertThat(trashed.isDeleted()).isFalse();
    }

    @Test
    void restoreRequiresATrashedAssignment() {
        when(assignments.findByIdAndDeletedAtIsNotNull(9L)).thenReturn(Mono.empty());

        StepVerifier.create(service.restore(9L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verify(assignments, never()).delete(any(TeachingAssignment.class));
    }

    @Test
    void hardDeleteRemovesATrashedAssignment() {
        TeachingAssignment trashed = assignment(9L, LocalDateTime.now());
        when(assignments.findByIdAndDeletedAtIsNotNull(9L)).thenReturn(Mono.just(trashed));
        when(assignments.delete(trashed)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(9L)).verifyComplete();

        verify(assignments).delete(trashed);
    }

    @Test
    void hardDeleteRequiresATrashedAssignment() {
        when(assignments.findByIdAndDeletedAtIsNotNull(9L)).thenReturn(Mono.empty());

        StepVerifier.create(service.hardDelete(9L))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verify(assignments, never()).delete(any(TeachingAssignment.class));
    }

    // ── What the signed-in account teaches ──────────────────────────────────

    @Test
    void findMineForAccountReturnsTheAssignmentsOfTheTeacherProfile() {
        when(teacherRepository.findByAccountIdAndDeletedAtIsNull(ACCOUNT_ID))
                .thenReturn(Mono.just(teacher(TEACHER_ID, ACCOUNT_ID)));
        when(assignments.findAllByTeacherIdAndDeletedAtIsNull(TEACHER_ID))
                .thenReturn(Flux.just(assignment(9L, null)));

        StepVerifier.create(service.findMineForAccount(ACCOUNT_ID))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(9L);
                    assertThat(response.classId()).isEqualTo(CLASS_ID);
                })
                .verifyComplete();
    }

    @Test
    void findMineForAccountIsEmptyWithoutATeacherProfile() {
        when(teacherRepository.findByAccountIdAndDeletedAtIsNull(ACCOUNT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.findMineForAccount(ACCOUNT_ID)).verifyComplete();
    }

    // ── requireTeaches: the statement authorization rule ────────────────────

    @Test
    void requireTeachesAllowsAnAssignedTeacher() {
        givenTeacherProfileExists();
        givenClassExists();
        when(assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.just(true));

        StepVerifier.create(service.requireTeaches(ACCOUNT_ID, false, CLASS_ID, SUBJECT_ID)).verifyComplete();
    }

    @Test
    void requireTeachesMatchesTheSchoolYearOfTheClassNotACallerSuppliedOne() {
        givenTeacherProfileExists();
        givenClassExists();
        when(assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.just(true));

        StepVerifier.create(service.requireTeaches(ACCOUNT_ID, false, CLASS_ID, SUBJECT_ID)).verifyComplete();

        verify(assignments)
                .existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                        TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID);
    }

    @Test
    void requireTeachesRejectsATeacherAssignedToAnotherClass() {
        givenTeacherProfileExists();
        givenClassExists();
        when(assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.just(false));

        StepVerifier.create(service.requireTeaches(ACCOUNT_ID, false, CLASS_ID, SUBJECT_ID))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
    }

    @Test
    void requireTeachesRejectsATrashedAssignment() {
        givenTeacherProfileExists();
        givenClassExists();
        when(assignments.existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID)).thenReturn(Mono.just(false));

        StepVerifier.create(service.requireTeaches(ACCOUNT_ID, false, CLASS_ID, SUBJECT_ID))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
    }

    @Test
    void requireTeachesRejectsAnAccountWithoutATeacherProfile() {
        when(teacherRepository.findByAccountIdAndDeletedAtIsNull(ACCOUNT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.requireTeaches(ACCOUNT_ID, false, CLASS_ID, SUBJECT_ID))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        verifyNoInteractions(assignments);
    }

    @Test
    void requireTeachesRejectsAClassThatNoLongerExists() {
        givenTeacherProfileExists();
        when(classRepository.findByIdAndDeletedAtIsNull(CLASS_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.requireTeaches(ACCOUNT_ID, false, CLASS_ID, SUBJECT_ID))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();

        verify(assignments, never())
                .existsByTeacherIdAndClassIdAndSubjectIdAndSchoolYearIdAndDeletedAtIsNull(
                        TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID);
    }

    @Test
    void requireTeachesLetsAnAdministratorThroughWithoutConsultingAnything() {
        // An administrator administers the school instead of teaching it: they
        // hold no assignments and need not even have a teacher profile, so the
        // rule must not reach for either. Reaching for it produced
        // "Only teachers can build statements" for every administrator.
        StepVerifier.create(service.requireTeaches(ACCOUNT_ID, true, CLASS_ID, SUBJECT_ID))
                .verifyComplete();

        verifyNoInteractions(teacherRepository, classRepository, assignments);
    }

    @Test
    void requireTeachesImposesNothingOnAStatementWithoutAClass() {
        StepVerifier.create(service.requireTeaches(ACCOUNT_ID, false, null, SUBJECT_ID)).verifyComplete();

        verifyNoInteractions(teacherRepository, classRepository, assignments);
    }

    @Test
    void requireTeachesImposesNothingOnAStatementWithoutASubject() {
        StepVerifier.create(service.requireTeaches(ACCOUNT_ID, false, CLASS_ID, null)).verifyComplete();

        verifyNoInteractions(teacherRepository, classRepository, assignments);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void givenReferencesExist() {
        givenTeacherExists();
        givenClassExists();
        givenSubjectExists();
    }

    private void givenTeacherExists() {
        when(teacherRepository.findByIdAndDeletedAtIsNull(TEACHER_ID))
                .thenReturn(Mono.just(teacher(TEACHER_ID, ACCOUNT_ID)));
    }

    private void givenTeacherProfileExists() {
        when(teacherRepository.findByAccountIdAndDeletedAtIsNull(ACCOUNT_ID))
                .thenReturn(Mono.just(teacher(TEACHER_ID, ACCOUNT_ID)));
    }

    private void givenClassExists() {
        when(classRepository.findByIdAndDeletedAtIsNull(CLASS_ID))
                .thenReturn(Mono.just(klass(CLASS_ID, SCHOOL_YEAR_ID)));
    }

    private void givenClassAndSubjectExist() {
        givenClassExists();
        givenSubjectExists();
    }

    private void givenSubjectExists() {
        Subject subject = new Subject();
        subject.setId(SUBJECT_ID);
        subject.setName("Matemática");
        when(subjectRepository.findByIdAndDeletedAtIsNull(SUBJECT_ID)).thenReturn(Mono.just(subject));
    }

    private TeachingAssignmentRequest request(String tutorStyle) {
        return new TeachingAssignmentRequest(
                TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID, tutorStyle);
    }

    private TeachingAssignment assignment(Long id, LocalDateTime deletedAt) {
        TeachingAssignment assignment =
                new TeachingAssignment(TEACHER_ID, CLASS_ID, SUBJECT_ID, SCHOOL_YEAR_ID);
        assignment.setId(id);
        assignment.setDeletedAt(deletedAt);
        return assignment;
    }

    private Teacher teacher(Long id, Long accountId) {
        Teacher teacher = new Teacher();
        teacher.setId(id);
        teacher.setAccountId(accountId);
        teacher.setFirstName("Ana");
        teacher.setLastName("Costa");
        return teacher;
    }

    private Class klass(Long id, Long schoolYearId) {
        Class klass = new Class();
        klass.setId(id);
        klass.setGrade(12);
        klass.setSchoolYearId(schoolYearId);
        return klass;
    }

    private TeachingAssignment withId(TeachingAssignment assignment, Long id) {
        assignment.setId(id);
        return assignment;
    }
}
