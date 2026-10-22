package ao.creativemode.kixi.institutions.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import ao.creativemode.kixi.academic.model.Class;
import ao.creativemode.kixi.academic.model.SchoolYear;
import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.identity.model.Account;
import ao.creativemode.kixi.identity.model.AccountRole;
import ao.creativemode.kixi.identity.model.Role;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.repository.AccountRoleRepository;
import ao.creativemode.kixi.identity.repository.RoleRepository;
import ao.creativemode.kixi.institutions.dto.enrollment.EnrollRequest;
import ao.creativemode.kixi.institutions.model.Enrollment;
import ao.creativemode.kixi.institutions.repository.EnrollmentRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class EnrollmentServiceTest {

    private static final String CODE = "ABCD1234";

    private EnrollmentRepository enrollments;
    private AccountRepository accounts;
    private ClassRepository classes;
    private SchoolYearRepository schoolYears;
    private AccountRoleRepository accountRoles;
    private RoleRepository roles;
    private EnrollmentCodeService enrollmentCodeService;
    private InstitutionAccessService institutionAccessService;
    private TeachingAssignmentService teachingAssignmentService;
    private EnrollmentService service;

    @BeforeEach
    void setUp() {
        enrollments = mock(EnrollmentRepository.class);
        accounts = mock(AccountRepository.class);
        classes = mock(ClassRepository.class);
        schoolYears = mock(SchoolYearRepository.class);
        accountRoles = mock(AccountRoleRepository.class);
        roles = mock(RoleRepository.class);
        enrollmentCodeService = mock(EnrollmentCodeService.class);
        institutionAccessService = mock(InstitutionAccessService.class);
        teachingAssignmentService = mock(TeachingAssignmentService.class);
        service = new EnrollmentService(
            enrollments, accounts, classes, schoolYears, accountRoles, roles, enrollmentCodeService,
            institutionAccessService, teachingAssignmentService);
    }

    /** A code that redeems to {@code classId}, as {@code EnrollmentCodeService.redeem} would resolve it. */
    private void codeRedeemsTo(Long classId) {
        when(enrollmentCodeService.redeem(CODE))
            .thenReturn(Mono.just(new EnrollmentCodeService.Redemption(99L, classId)));
    }

    /**
     * The target account holds exactly the roles named. The role check reads the live role
     * links, so this stubs both hops the way {@code requireStudentRole} queries them.
     */
    private void accountHasRoles(Long accountId, Long roleId, String... names) {
        when(accountRoles.findByAccountIdAndDeletedAtIsNull(accountId))
            .thenReturn(Flux.just(new AccountRole(accountId, roleId)));
        when(roles.findById(roleId)).thenAnswer(invocation -> {
            Role role = new Role();
            role.setId(roleId);
            role.setName(names.length > 0 ? names[0] : "STUDENT");
            return Mono.just(role);
        });
    }

    @Test
    void studentEnrollsThemselvesWithACode() {
        when(accounts.findById(42L)).thenReturn(Mono.just(activeAccount(42L)));
        accountHasRoles(42L, 1L, "STUDENT");
        codeRedeemsTo(7L);
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(activeClass(7L, 3L, 2024L)));
        when(schoolYears.findByIdAndDeletedAtIsNull(2024L)).thenReturn(Mono.just(schoolYear(2024L)));
        when(enrollments.findFirstByAccountIdAndSchoolYearId(42L, 2024L)).thenReturn(Mono.empty());
        when(enrollments.save(any(Enrollment.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.enroll(42L, false, false, new EnrollRequest(null, null, null, CODE)))
            .expectNextMatches(response ->
                response.accountId().equals(42L)
                    && response.classId().equals(7L)
                    && response.schoolYearId().equals(2024L)
                    && response.status().equals(Enrollment.STATUS_ACTIVE))
            .verifyComplete();
    }

    /**
     * A self-enroll resolves the class strictly from the redeemed code (#255): the
     * classId/schoolYearId the request also carries, if any, is never trusted.
     */
    @Test
    void selfEnrollIgnoresClientSuppliedClassIdInFavorOfTheCode() {
        when(accounts.findById(42L)).thenReturn(Mono.just(activeAccount(42L)));
        accountHasRoles(42L, 1L, "STUDENT");
        codeRedeemsTo(7L);
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(activeClass(7L, 3L, 2024L)));
        when(schoolYears.findByIdAndDeletedAtIsNull(2024L)).thenReturn(Mono.just(schoolYear(2024L)));
        when(enrollments.findFirstByAccountIdAndSchoolYearId(42L, 2024L)).thenReturn(Mono.empty());
        when(enrollments.save(any(Enrollment.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // classId 999 / schoolYearId 999 are a different class the caller does not have a code for.
        StepVerifier.create(service.enroll(42L, false, false, new EnrollRequest(null, 999L, 999L, CODE)))
            .expectNextMatches(response -> response.classId().equals(7L) && response.schoolYearId().equals(2024L))
            .verifyComplete();
        verify(classes, never()).findByIdAndDeletedAtIsNull(999L);
    }

    @Test
    void selfEnrollWithoutACodeIsRejected() {
        when(accounts.findById(42L)).thenReturn(Mono.just(activeAccount(42L)));
        accountHasRoles(42L, 1L, "STUDENT");
        when(enrollmentCodeService.redeem(null))
            .thenReturn(Mono.error(ApiException.badRequest("An enrollment code is required")));

        StepVerifier.create(service.enroll(42L, false, false, new EnrollRequest(null, 7L, 2024L, null)))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.BAD_REQUEST);
        verify(enrollments, never()).save(any(Enrollment.class));
    }

    @Test
    void selfEnrollPropagatesAnInvalidCodeError() {
        when(accounts.findById(42L)).thenReturn(Mono.just(activeAccount(42L)));
        accountHasRoles(42L, 1L, "STUDENT");
        when(enrollmentCodeService.redeem("WRONG"))
            .thenReturn(Mono.error(ApiException.badRequest("Invalid, expired, revoked or already used enrollment code")));

        StepVerifier.create(service.enroll(42L, false, false, new EnrollRequest(null, null, null, "WRONG")))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.BAD_REQUEST);
        verify(enrollments, never()).save(any(Enrollment.class));
    }

    @Test
    void staffEnrollsAnotherAccount() {
        accountHasRoles(43L, 1L, "STUDENT");
        when(accounts.findById(43L)).thenReturn(Mono.just(activeAccount(43L)));
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(activeClass(7L, 3L, 2024L)));
        when(schoolYears.findByIdAndDeletedAtIsNull(2024L)).thenReturn(Mono.just(schoolYear(2024L)));
        when(enrollments.findFirstByAccountIdAndSchoolYearId(43L, 2024L)).thenReturn(Mono.empty());
        when(enrollments.save(any(Enrollment.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.enroll(7L, true, false, new EnrollRequest(43L, 7L, 2024L, null)))
            .expectNextMatches(response -> response.accountId().equals(43L))
            .verifyComplete();
        verify(enrollmentCodeService, never()).redeem(any());
    }

    @Test
    void affiliatedTeacherCanEnrollAnotherAccountInTheirInstitution() {
        accountHasRoles(43L, 1L, "STUDENT");
        when(accounts.findById(43L)).thenReturn(Mono.just(activeAccount(43L)));
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(activeClass(7L, 3L, 2024L)));
        when(institutionAccessService.requireTeacherAffiliatedToInstitution(9L, 3L)).thenReturn(Mono.empty());
        when(schoolYears.findByIdAndDeletedAtIsNull(2024L)).thenReturn(Mono.just(schoolYear(2024L)));
        when(enrollments.findFirstByAccountIdAndSchoolYearId(43L, 2024L)).thenReturn(Mono.empty());
        when(enrollments.save(any(Enrollment.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.enroll(9L, false, true, new EnrollRequest(43L, 7L, 2024L, null)))
            .expectNextMatches(response -> response.accountId().equals(43L))
            .verifyComplete();
        verify(institutionAccessService).requireTeacherAffiliatedToInstitution(9L, 3L);
    }

    @Test
    void teacherCannotEnrollAcrossInstitutions() {
        accountHasRoles(43L, 1L, "STUDENT");
        when(accounts.findById(43L)).thenReturn(Mono.just(activeAccount(43L)));
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(activeClass(7L, 3L, 2024L)));
        when(institutionAccessService.requireTeacherAffiliatedToInstitution(9L, 3L))
            .thenReturn(Mono.error(ApiException.notFound("Institution not found")));

        StepVerifier.create(service.enroll(9L, false, true, new EnrollRequest(43L, 7L, 2024L, null)))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.NOT_FOUND);
        verify(enrollments, never()).save(any(Enrollment.class));
    }

    @Test
    void studentCannotEnrollAnotherAccount() {
        StepVerifier.create(service.enroll(42L, false, false, new EnrollRequest(43L, 7L, 2024L, null)))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.FORBIDDEN);

        verify(enrollments, never()).save(any(Enrollment.class));
    }

    @Test
    void secondActiveEnrollmentInSameYearConflicts() {
        accountHasRoles(42L, 1L, "STUDENT");
        Enrollment active = new Enrollment(42L, 5L, 2024L);
        active.setId(1L);
        when(accounts.findById(42L)).thenReturn(Mono.just(activeAccount(42L)));
        codeRedeemsTo(7L);
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(activeClass(7L, 3L, 2024L)));
        when(schoolYears.findByIdAndDeletedAtIsNull(2024L)).thenReturn(Mono.just(schoolYear(2024L)));
        when(enrollments.findFirstByAccountIdAndSchoolYearId(42L, 2024L)).thenReturn(Mono.just(active));

        StepVerifier.create(service.enroll(42L, false, false, new EnrollRequest(null, null, null, CODE)))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.CONFLICT);
    }

    @Test
    void cancelledEnrollmentIsRestoredInNewClass() {
        accountHasRoles(42L, 1L, "STUDENT");
        Enrollment cancelled = new Enrollment(42L, 5L, 2024L);
        cancelled.setId(1L);
        cancelled.markAsDeleted();
        when(accounts.findById(42L)).thenReturn(Mono.just(activeAccount(42L)));
        codeRedeemsTo(7L);
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(activeClass(7L, 3L, 2024L)));
        when(schoolYears.findByIdAndDeletedAtIsNull(2024L)).thenReturn(Mono.just(schoolYear(2024L)));
        when(enrollments.findFirstByAccountIdAndSchoolYearId(42L, 2024L)).thenReturn(Mono.just(cancelled));
        when(enrollments.save(any(Enrollment.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.enroll(42L, false, false, new EnrollRequest(null, null, null, CODE)))
            .expectNextMatches(response ->
                response.classId().equals(7L) && response.status().equals(Enrollment.STATUS_ACTIVE))
            .verifyComplete();
    }

    @Test
    void staffClassOutsideSchoolYearIsRejected() {
        accountHasRoles(43L, 1L, "STUDENT");
        when(accounts.findById(43L)).thenReturn(Mono.just(activeAccount(43L)));
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(activeClass(7L, 3L, 2023L)));

        StepVerifier.create(service.enroll(7L, true, false, new EnrollRequest(43L, 7L, 2024L, null)))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void staffEnrollWithoutClassOrSchoolYearIsRejected() {
        accountHasRoles(43L, 1L, "STUDENT");
        when(accounts.findById(43L)).thenReturn(Mono.just(activeAccount(43L)));

        StepVerifier.create(service.enroll(7L, true, false, new EnrollRequest(43L, null, null, null)))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.BAD_REQUEST);
        verify(enrollments, never()).save(any(Enrollment.class));
    }

    @Test
    void staffCannotEnrollAnAccountWithoutTheStudentRole() {
        // The bug in #148: an admin could enroll a teacher account into a class.
        when(accounts.findById(43L)).thenReturn(Mono.just(activeAccount(43L)));
        accountHasRoles(43L, 2L, "TEACHER");

        StepVerifier.create(service.enroll(7L, true, false, new EnrollRequest(43L, 7L, 2024L, null)))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.BAD_REQUEST);
        verify(enrollments, never()).save(any(Enrollment.class));
    }

    @Test
    void accountWithoutAnyRoleIsRejected() {
        when(accounts.findById(43L)).thenReturn(Mono.just(activeAccount(43L)));
        when(accountRoles.findByAccountIdAndDeletedAtIsNull(43L)).thenReturn(Flux.empty());

        StepVerifier.create(service.enroll(7L, true, false, new EnrollRequest(43L, 7L, 2024L, null)))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.BAD_REQUEST);
        verify(enrollments, never()).save(any(Enrollment.class));
    }

    @Test
    void softDeletedStudentRoleDoesNotCount() {
        // A trashed role link is not returned by the live query, so the account ends up
        // with no roles at all and is rejected — same as a role that was never granted.
        when(accounts.findById(43L)).thenReturn(Mono.just(activeAccount(43L)));
        when(accountRoles.findByAccountIdAndDeletedAtIsNull(43L)).thenReturn(Flux.empty());

        StepVerifier.create(service.enroll(7L, true, false, new EnrollRequest(43L, 7L, 2024L, null)))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownClassFromARedeemedCodeIsNotFound() {
        // The class behind a once-valid code may have been soft-deleted since; the
        // redemption itself already succeeded (and consumed a use) by this point.
        when(accounts.findById(42L)).thenReturn(Mono.just(activeAccount(42L)));
        accountHasRoles(42L, 1L, "STUDENT");
        codeRedeemsTo(9L);
        when(classes.findByIdAndDeletedAtIsNull(9L)).thenReturn(Mono.empty());

        StepVerifier.create(service.enroll(42L, false, false, new EnrollRequest(null, null, null, CODE)))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.NOT_FOUND);
    }

    @Test
    void unknownClassFromAStaffRequestIsNotFound() {
        when(accounts.findById(42L)).thenReturn(Mono.just(activeAccount(42L)));
        accountHasRoles(42L, 1L, "STUDENT");
        when(classes.findByIdAndDeletedAtIsNull(9L)).thenReturn(Mono.empty());

        StepVerifier.create(service.enroll(7L, true, false, new EnrollRequest(42L, 9L, 2024L, null)))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.NOT_FOUND);
    }

    @Test
    void studentListsOwnEnrollments() {
        Enrollment enrollment = new Enrollment(42L, 7L, 2024L);
        enrollment.setId(1L);
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(42L)).thenReturn(Flux.just(enrollment));

        StepVerifier.create(service.findVisible(42L, false, false, null))
            .expectNextMatches(response -> response.id().equals(1L))
            .verifyComplete();
    }

    @Test
    void studentCannotListAnotherAccount() {
        StepVerifier.create(service.findVisible(42L, false, false, 43L))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.FORBIDDEN);
    }

    @Test
    void staffListsAnotherAccount() {
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(43L)).thenReturn(Flux.empty());

        StepVerifier.create(service.findVisible(7L, true, false, 43L)).verifyComplete();
    }

    @Test
    void teacherListsAnotherAccountOnlyWhenAssignedToItsClass() {
        Enrollment enrollment = new Enrollment(43L, 7L, 2024L);
        enrollment.setId(1L);
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(43L)).thenReturn(Flux.just(enrollment));
        when(teachingAssignmentService.hasAssignedToClass(9L, 7L)).thenReturn(Mono.just(true));

        StepVerifier.create(service.findVisible(9L, false, true, 43L))
            .expectNextMatches(response -> response.id().equals(1L))
            .verifyComplete();
        verify(teachingAssignmentService).hasAssignedToClass(9L, 7L);
    }

    @Test
    void teacherListsOnlyAssignedEnrollmentsFromAMixedAccount() {
        Enrollment visible = new Enrollment(43L, 7L, 2024L);
        visible.setId(1L);
        Enrollment hidden = new Enrollment(43L, 8L, 2025L);
        hidden.setId(2L);
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(43L))
            .thenReturn(Flux.just(visible, hidden));
        when(teachingAssignmentService.hasAssignedToClass(9L, 7L)).thenReturn(Mono.just(true));
        when(teachingAssignmentService.hasAssignedToClass(9L, 8L)).thenReturn(Mono.just(false));

        StepVerifier.create(service.findVisible(9L, false, true, 43L))
            .expectNextMatches(response -> response.id().equals(1L))
            .verifyComplete();
        verify(teachingAssignmentService).hasAssignedToClass(9L, 7L);
        verify(teachingAssignmentService).hasAssignedToClass(9L, 8L);
    }

    @Test
    void teacherGetsNotFoundWhenNoEnrollmentIsInTheirAssignedClasses() {
        Enrollment hidden = new Enrollment(43L, 8L, 2025L);
        hidden.setId(2L);
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(43L)).thenReturn(Flux.just(hidden));
        when(teachingAssignmentService.hasAssignedToClass(9L, 8L)).thenReturn(Mono.just(false));

        StepVerifier.create(service.findVisible(9L, false, true, 43L))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.NOT_FOUND);
    }

    @Test
    void teacherCannotCancelAnEnrollmentOutsideTheirAssignments() {
        Enrollment enrollment = new Enrollment(43L, 7L, 2024L);
        enrollment.setId(1L);
        when(enrollments.findById(1L)).thenReturn(Mono.just(enrollment));
        when(teachingAssignmentService.requireAssignedToClass(9L, 7L))
            .thenReturn(Mono.error(ApiException.notFound("Enrollment not found")));

        StepVerifier.create(service.cancel(9L, false, true, 1L))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.NOT_FOUND);
        verify(enrollments, never()).save(any(Enrollment.class));
    }

    private static Account activeAccount(Long id) {
        Account account = new Account();
        account.setId(id);
        account.setActive(Boolean.TRUE);
        return account;
    }

    private static Class activeClass(Long id, Long courseId, Long schoolYearId) {
        Class clazz = new Class();
        clazz.setId(id);
        clazz.setCourseId(courseId);
        clazz.setSchoolYearId(schoolYearId);
        clazz.setInstitutionId(3L);
        return clazz;
    }

    private static SchoolYear schoolYear(Long id) {
        SchoolYear year = new SchoolYear();
        year.setId(id);
        return year;
    }
}
