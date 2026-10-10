package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.identity.model.Role;
import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.identity.repository.AccountRoleRepository;
import ao.creativemode.kixi.identity.repository.RoleRepository;
import ao.creativemode.kixi.institutions.dto.enrollment.EnrollRequest;
import ao.creativemode.kixi.institutions.dto.enrollment.EnrollmentResponse;
import ao.creativemode.kixi.institutions.model.Enrollment;
import ao.creativemode.kixi.institutions.repository.EnrollmentRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Student enrollments in classes.
 *
 * <p>A student enrolls themselves; ADMIN/TEACHER may enroll any account.
 * At most one active enrollment per account and school year exists: a
 * cancelled enrollment is restored (and moved to the new class) instead of
 * duplicated.</p>
 *
 * <p>The account being enrolled must hold the STUDENT role (#148). Checking the
 * caller's right to enroll an account is not the same as checking that the
 * account is a student: without this, an admin could enroll a teacher account
 * into a class and the class roll would then be wrong for everyone downstream.</p>
 */
@Service
public class EnrollmentService {

    private static final String STUDENT_ROLE = "STUDENT";

    private final EnrollmentRepository enrollments;
    private final AccountRepository accountRepository;
    private final ClassRepository classRepository;
    private final SchoolYearRepository schoolYearRepository;
    private final AccountRoleRepository accountRoleRepository;
    private final RoleRepository roleRepository;

    public EnrollmentService(
        EnrollmentRepository enrollments,
        AccountRepository accountRepository,
        ClassRepository classRepository,
        SchoolYearRepository schoolYearRepository,
        AccountRoleRepository accountRoleRepository,
        RoleRepository roleRepository
    ) {
        this.enrollments = enrollments;
        this.accountRepository = accountRepository;
        this.classRepository = classRepository;
        this.schoolYearRepository = schoolYearRepository;
        this.accountRoleRepository = accountRoleRepository;
        this.roleRepository = roleRepository;
    }

    public Mono<EnrollmentResponse> enroll(Long callerAccountId, boolean staff, EnrollRequest request) {
        Long targetAccountId = request.accountId() != null ? request.accountId() : callerAccountId;
        if (!targetAccountId.equals(callerAccountId) && !staff) {
            return Mono.error(ApiException.forbidden("Only ADMIN or TEACHER can enroll another account"));
        }

        return Mono.defer(() -> accountRepository.findById(targetAccountId)
                .filter(account -> account.getDeletedAt() == null && Boolean.TRUE.equals(account.getActive()))
                .switchIfEmpty(Mono.error(ApiException.notFound("Account not found"))))
            .then(Mono.defer(() -> requireStudentRole(targetAccountId)))
            .then(Mono.defer(() -> classRepository.findByIdAndDeletedAtIsNull(request.classId())
                .switchIfEmpty(Mono.error(ApiException.notFound("Class not found")))))
            .flatMap(clazz -> {
                if (!clazz.getSchoolYearId().equals(request.schoolYearId())) {
                    return Mono.error(ApiException.badRequest("Class does not belong to the given school year"));
                }
                return schoolYearRepository.findByIdAndDeletedAtIsNull(request.schoolYearId())
                    .switchIfEmpty(Mono.error(ApiException.notFound("School year not found")))
                    .then(Mono.defer(() ->
                        enrollments.findFirstByAccountIdAndSchoolYearId(targetAccountId, request.schoolYearId())
                            .flatMap(existing -> {
                                if (!existing.isDeleted()) {
                                    return Mono.<Enrollment>error(ApiException.conflict(
                                        "Account already has an active enrollment for this school year"));
                                }
                                existing.restore();
                                existing.setClassId(request.classId());
                                return enrollments.save(existing);
                            })
                            .switchIfEmpty(Mono.defer(() -> enrollments.save(
                                new Enrollment(targetAccountId, request.classId(), request.schoolYearId()))))
                    ));
            })
            .map(EnrollmentService::toResponse);
    }

    /**
     * Rejects an enrollment whose target account is not a student.
     *
     * <p>Same query as {@code MeService.loadRoleNames}: the live role links, then the
     * role, skipping soft-deleted ones. Done after the account exists and before the
     * class, so a caller enrolling a teacher gets a clear 400 rather than a conflict on a
     * class they were never going to join.</p>
     */
    private Mono<Void> requireStudentRole(Long accountId) {
        return accountRoleRepository.findByAccountIdAndDeletedAtIsNull(accountId)
            .flatMap(link -> roleRepository.findById(link.getRoleId()))
            .filter(role -> role.getDeletedAt() == null)
            .map(Role::getName)
            .any(STUDENT_ROLE::equals)
            .flatMap(isStudent -> isStudent
                ? Mono.empty()
                : Mono.error(ApiException.badRequest("Account does not have the STUDENT role")));
    }

    /**
     * Active enrollments visible to the caller: their own, or another
     * account's when the caller is staff.
     */
    public Flux<EnrollmentResponse> findVisible(Long callerAccountId, boolean staff, Long accountId) {
        Long targetAccountId = accountId != null ? accountId : callerAccountId;
        if (!targetAccountId.equals(callerAccountId) && !staff) {
            return Flux.error(ApiException.forbidden("Only ADMIN or TEACHER can list another account's enrollments"));
        }
        return enrollments.findAllByAccountIdAndDeletedAtIsNull(targetAccountId).map(EnrollmentService::toResponse);
    }

    public Mono<EnrollmentResponse> cancel(Long callerAccountId, boolean staff, Long enrollmentId) {
        return enrollments.findById(enrollmentId)
            .filter(enrollment -> !enrollment.isDeleted())
            .switchIfEmpty(Mono.error(ApiException.notFound("Enrollment not found")))
            .flatMap(enrollment -> {
                if (!enrollment.getAccountId().equals(callerAccountId) && !staff) {
                    return Mono.error(ApiException.forbidden("Only ADMIN or TEACHER can cancel another account's enrollment"));
                }
                enrollment.markAsDeleted();
                return enrollments.save(enrollment);
            })
            .map(EnrollmentService::toResponse);
    }

    static EnrollmentResponse toResponse(Enrollment enrollment) {
        return new EnrollmentResponse(
            enrollment.getId(),
            enrollment.getAccountId(),
            enrollment.getClassId(),
            enrollment.getSchoolYearId(),
            enrollment.getStatus()
        );
    }
}
