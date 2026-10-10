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

    private EnrollmentRepository enrollments;
    private AccountRepository accounts;
    private ClassRepository classes;
    private SchoolYearRepository schoolYears;
    private AccountRoleRepository accountRoles;
    private RoleRepository roles;
    private EnrollmentService service;

    @BeforeEach
    void setUp() {
        enrollments = mock(EnrollmentRepository.class);
        accounts = mock(AccountRepository.class);
        classes = mock(ClassRepository.class);
        schoolYears = mock(SchoolYearRepository.class);
        accountRoles = mock(AccountRoleRepository.class);
        roles = mock(RoleRepository.class);
        service = new EnrollmentService(
            enrollments, accounts, classes, schoolYears, accountRoles, roles);
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
    void studentEnrollsThemselves() {
        when(accounts.findById(42L)).thenReturn(Mono.just(activeAccount(42L)));
        accountHasRoles(42L, 1L, "STUDENT");
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(activeClass(7L, 3L, 2024L)));
        when(schoolYears.findByIdAndDeletedAtIsNull(2024L)).thenReturn(Mono.just(schoolYear(2024L)));
        when(enrollments.findFirstByAccountIdAndSchoolYearId(42L, 2024L)).thenReturn(Mono.empty());
        when(enrollments.save(any(Enrollment.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.enroll(42L, false, new EnrollRequest(null, 7L, 2024L)))
            .expectNextMatches(response ->
                response.accountId().equals(42L)
                    && response.classId().equals(7L)
                    && response.schoolYearId().equals(2024L)
                    && response.status().equals(Enrollment.STATUS_ACTIVE))
            .verifyComplete();
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

        StepVerifier.create(service.enroll(7L, true, new EnrollRequest(43L, 7L, 2024L)))
            .expectNextMatches(response -> response.accountId().equals(43L))
            .verifyComplete();
    }

    @Test
    void studentCannotEnrollAnotherAccount() {
        StepVerifier.create(service.enroll(42L, false, new EnrollRequest(43L, 7L, 2024L)))
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
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(activeClass(7L, 3L, 2024L)));
        when(schoolYears.findByIdAndDeletedAtIsNull(2024L)).thenReturn(Mono.just(schoolYear(2024L)));
        when(enrollments.findFirstByAccountIdAndSchoolYearId(42L, 2024L)).thenReturn(Mono.just(active));

        StepVerifier.create(service.enroll(42L, false, new EnrollRequest(null, 7L, 2024L)))
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
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(activeClass(7L, 3L, 2024L)));
        when(schoolYears.findByIdAndDeletedAtIsNull(2024L)).thenReturn(Mono.just(schoolYear(2024L)));
        when(enrollments.findFirstByAccountIdAndSchoolYearId(42L, 2024L)).thenReturn(Mono.just(cancelled));
        when(enrollments.save(any(Enrollment.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.enroll(42L, false, new EnrollRequest(null, 7L, 2024L)))
            .expectNextMatches(response ->
                response.classId().equals(7L) && response.status().equals(Enrollment.STATUS_ACTIVE))
            .verifyComplete();
    }

    @Test
    void classOutsideSchoolYearIsRejected() {
        accountHasRoles(42L, 1L, "STUDENT");
        when(accounts.findById(42L)).thenReturn(Mono.just(activeAccount(42L)));
        when(classes.findByIdAndDeletedAtIsNull(7L)).thenReturn(Mono.just(activeClass(7L, 3L, 2023L)));

        StepVerifier.create(service.enroll(42L, false, new EnrollRequest(null, 7L, 2024L)))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void staffCannotEnrollAnAccountWithoutTheStudentRole() {
        // The bug in #148: an admin could enroll a teacher account into a class.
        when(accounts.findById(43L)).thenReturn(Mono.just(activeAccount(43L)));
        accountHasRoles(43L, 2L, "TEACHER");

        StepVerifier.create(service.enroll(7L, true, new EnrollRequest(43L, 7L, 2024L)))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.BAD_REQUEST);
        verify(enrollments, never()).save(any(Enrollment.class));
    }

    @Test
    void accountWithoutAnyRoleIsRejected() {
        when(accounts.findById(43L)).thenReturn(Mono.just(activeAccount(43L)));
        when(accountRoles.findByAccountIdAndDeletedAtIsNull(43L)).thenReturn(Flux.empty());

        StepVerifier.create(service.enroll(7L, true, new EnrollRequest(43L, 7L, 2024L)))
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

        StepVerifier.create(service.enroll(7L, true, new EnrollRequest(43L, 7L, 2024L)))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownClassIsNotFound() {
        when(accounts.findById(42L)).thenReturn(Mono.just(activeAccount(42L)));
        accountHasRoles(42L, 1L, "STUDENT");
        when(classes.findByIdAndDeletedAtIsNull(9L)).thenReturn(Mono.empty());

        StepVerifier.create(service.enroll(42L, false, new EnrollRequest(null, 9L, 2024L)))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.NOT_FOUND);
    }

    @Test
    void studentListsOwnEnrollments() {
        Enrollment enrollment = new Enrollment(42L, 7L, 2024L);
        enrollment.setId(1L);
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(42L)).thenReturn(Flux.just(enrollment));

        StepVerifier.create(service.findVisible(42L, false, null))
            .expectNextMatches(response -> response.id().equals(1L))
            .verifyComplete();
    }

    @Test
    void studentCannotListAnotherAccount() {
        StepVerifier.create(service.findVisible(42L, false, 43L))
            .verifyErrorMatches(error -> error instanceof ApiException api
                && api.getStatus() == HttpStatus.FORBIDDEN);
    }

    @Test
    void staffListsAnotherAccount() {
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(43L)).thenReturn(Flux.empty());

        StepVerifier.create(service.findVisible(7L, true, 43L)).verifyComplete();
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
        return clazz;
    }

    private static SchoolYear schoolYear(Long id) {
        SchoolYear year = new SchoolYear();
        year.setId(id);
        return year;
    }
}
