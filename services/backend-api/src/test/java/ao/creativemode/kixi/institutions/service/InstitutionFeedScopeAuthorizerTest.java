package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.identity.model.Teacher;
import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.institutions.model.Enrollment;
import ao.creativemode.kixi.institutions.model.TeachingAssignment;
import ao.creativemode.kixi.institutions.repository.EnrollmentRepository;
import ao.creativemode.kixi.institutions.repository.TeachingAssignmentRepository;
import ao.creativemode.kixi.shared.service.FeedScopeAuthorizer.FeedScope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where the feed's school isolation actually lives now that it is behind a
 * door (issue #120, review point 3): the ranking service and the feed both
 * take what this returns at face value, so the institution/class/course/
 * school-year sets it builds are checked here.
 */
class InstitutionFeedScopeAuthorizerTest {

    private static final Long ACCOUNT_ID = 42L;
    // Deliberately not equal to ACCOUNT_ID: teaching_assignments.teacher_id points
    // at teachers.id, never at the account directly, and a test that let the two
    // coincide would not have caught InstitutionFeedScopeAuthorizer skipping the
    // teachers lookup and querying assignments by the account id itself.
    private static final Long TEACHER_ID = 900L;
    private static final Long CLASS_ID = 10L;
    private static final Long OTHER_CLASS_ID = 20L;
    private static final Long SCHOOL_A = 1L;
    private static final Long SCHOOL_B = 2L;

    private EnrollmentRepository enrollments;
    private TeachingAssignmentRepository teachingAssignments;
    private TeacherRepository teachers;
    private ClassRepository classes;
    private InstitutionFeedScopeAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        enrollments = mock(EnrollmentRepository.class);
        teachingAssignments = mock(TeachingAssignmentRepository.class);
        teachers = mock(TeacherRepository.class);
        classes = mock(ClassRepository.class);
        authorizer = new InstitutionFeedScopeAuthorizer(enrollments, teachingAssignments, teachers, classes);
        // No teacher profile by default: an account with none simply teaches nothing.
        when(teachers.findByAccountIdAndDeletedAtIsNull(ACCOUNT_ID)).thenReturn(Mono.empty());
    }

    @Test
    void aStudentsScopeComesFromTheirActiveEnrollment() {
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(ACCOUNT_ID))
                .thenReturn(Flux.just(enrollment(CLASS_ID, 2026L)));
        when(classes.findAllById(java.util.Set.of(CLASS_ID)))
                .thenReturn(Flux.just(klass(CLASS_ID, SCHOOL_A, 100L, 2026L)));

        StepVerifier.create(authorizer.scopeOf(ACCOUNT_ID))
                .assertNext(scope -> {
                    assertThat(scope.institutionIds()).containsExactly(SCHOOL_A);
                    assertThat(scope.classIds()).containsExactly(CLASS_ID);
                    assertThat(scope.courseIds()).containsExactly(100L);
                    assertThat(scope.schoolYearIds()).containsExactly(2026L);
                })
                .verifyComplete();
    }

    @Test
    void anAccountWithNoActiveEnrollmentOrAssignmentHasNoScope() {
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(ACCOUNT_ID)).thenReturn(Flux.empty());

        StepVerifier.create(authorizer.scopeOf(ACCOUNT_ID))
                .assertNext(scope -> assertThat(scope.isEmpty()).isTrue())
                .verifyComplete();
    }

    @Test
    void aCancelledEnrollmentIsNotInScope() {
        Enrollment cancelled = enrollment(CLASS_ID, 2026L);
        cancelled.markAsDeleted();
        // findAllByAccountIdAndDeletedAtIsNull already excludes cancelled rows at the
        // query level; this proves the extra in-memory filter does too, in case a
        // caller of this class ever bypasses that query.
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(ACCOUNT_ID)).thenReturn(Flux.just(cancelled));

        StepVerifier.create(authorizer.scopeOf(ACCOUNT_ID))
                .assertNext(scope -> assertThat(scope.isEmpty()).isTrue())
                .verifyComplete();
    }

    @Test
    void aTeachersScopeComesFromTheirTeachingAssignments() {
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(ACCOUNT_ID)).thenReturn(Flux.empty());
        when(teachers.findByAccountIdAndDeletedAtIsNull(ACCOUNT_ID)).thenReturn(Mono.just(teacher(TEACHER_ID)));
        when(teachingAssignments.findAllByTeacherIdAndDeletedAtIsNull(TEACHER_ID))
                .thenReturn(Flux.just(new TeachingAssignment(TEACHER_ID, CLASS_ID, 7L, 2026L)));
        when(classes.findAllById(java.util.Set.of(CLASS_ID)))
                .thenReturn(Flux.just(klass(CLASS_ID, SCHOOL_A, 100L, 2026L)));

        StepVerifier.create(authorizer.scopeOf(ACCOUNT_ID))
                .assertNext(scope -> assertThat(scope.classIds()).containsExactly(CLASS_ID))
                .verifyComplete();
    }

    @Test
    void twoSchoolsStayTwoSchoolsNeverOneMergedScope() {
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(ACCOUNT_ID))
                .thenReturn(Flux.just(enrollment(CLASS_ID, 2025L), enrollment(OTHER_CLASS_ID, 2026L)));
        when(classes.findAllById(java.util.Set.of(CLASS_ID, OTHER_CLASS_ID)))
                .thenReturn(Flux.just(
                        klass(CLASS_ID, SCHOOL_A, 100L, 2025L),
                        klass(OTHER_CLASS_ID, SCHOOL_B, 200L, 2026L)));

        StepVerifier.create(authorizer.scopeOf(ACCOUNT_ID))
                .assertNext(scope -> {
                    assertThat(scope.institutionIds()).containsExactlyInAnyOrder(SCHOOL_A, SCHOOL_B);
                    // Each school's own class is covered; a course id from school A
                    // never clears a post for school B, because covers() also checks
                    // the institution.
                    assertThat(scope.covers(SCHOOL_A, CLASS_ID, 999L, 999L)).isTrue();
                    assertThat(scope.covers(SCHOOL_B, 999L, 100L, 999L)).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void classScopeRefusesAClassTheAccountDoesNotHold() {
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(ACCOUNT_ID))
                .thenReturn(Flux.just(enrollment(CLASS_ID, 2026L)));
        when(classes.findAllById(java.util.Set.of(CLASS_ID)))
                .thenReturn(Flux.just(klass(CLASS_ID, SCHOOL_A, 100L, 2026L)));

        StepVerifier.create(authorizer.classScope(ACCOUNT_ID, OTHER_CLASS_ID)).verifyComplete();
    }

    @Test
    void classScopeResolvesCourseSchoolYearAndInstitutionFromTheClass() {
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(ACCOUNT_ID))
                .thenReturn(Flux.just(enrollment(CLASS_ID, 2026L)));
        when(classes.findAllById(java.util.Set.of(CLASS_ID)))
                .thenReturn(Flux.just(klass(CLASS_ID, SCHOOL_A, 100L, 2026L)));
        when(classes.findByIdAndDeletedAtIsNull(CLASS_ID))
                .thenReturn(Mono.just(klass(CLASS_ID, SCHOOL_A, 100L, 2026L)));

        StepVerifier.create(authorizer.classScope(ACCOUNT_ID, CLASS_ID))
                .assertNext(scope -> {
                    assertThat(scope.institutionId()).isEqualTo(SCHOOL_A);
                    assertThat(scope.courseId()).isEqualTo(100L);
                    assertThat(scope.schoolYearId()).isEqualTo(2026L);
                })
                .verifyComplete();
    }

    @Test
    void taughtClassesOfAStudentWithNoAssignmentsIsEmpty() {
        StepVerifier.create(authorizer.taughtClassesOf(ACCOUNT_ID))
                .assertNext(ids -> assertThat(ids).isEmpty())
                .verifyComplete();
    }

    private static Enrollment enrollment(Long classId, Long schoolYearId) {
        return new Enrollment(ACCOUNT_ID, classId, schoolYearId);
    }

    private static Teacher teacher(Long id) {
        Teacher teacher = new Teacher();
        teacher.setId(id);
        teacher.setAccountId(ACCOUNT_ID);
        return teacher;
    }

    private static Class klass(Long id, Long institutionId, Long courseId, Long schoolYearId) {
        Class klass = new Class();
        klass.setId(id);
        klass.setInstitutionId(institutionId);
        klass.setCourseId(courseId);
        klass.setSchoolYearId(schoolYearId);
        return klass;
    }
}
