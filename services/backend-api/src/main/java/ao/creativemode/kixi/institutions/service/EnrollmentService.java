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
 * <p>ADMIN may enroll any account directly. TEACHER may enroll accounts only in
 * an institution to which they are affiliated. A student enrolling <em>themselves</em>
 * no longer may: since #255, self-enroll requires an {@link EnrollmentCode} issued
 * by the school for that class, redeemed atomically by
 * {@link EnrollmentCodeService#redeem}, and the class/school year it resolves to
 * are what gets used — never whatever {@code classId}/{@code schoolYearId} the
 * request happened to carry. Before #255 a student could enroll in any class of
 * any school just by sending its id; that is exactly what a code closes off.</p>
 *
 * <p>At most one active enrollment per account and school year exists: a
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
    private final EnrollmentCodeService enrollmentCodeService;
    private final InstitutionAccessService institutionAccessService;
    private final TeachingAssignmentService teachingAssignmentService;

    public EnrollmentService(
        EnrollmentRepository enrollments,
        AccountRepository accountRepository,
        ClassRepository classRepository,
        SchoolYearRepository schoolYearRepository,
        AccountRoleRepository accountRoleRepository,
        RoleRepository roleRepository,
        EnrollmentCodeService enrollmentCodeService,
        InstitutionAccessService institutionAccessService,
        TeachingAssignmentService teachingAssignmentService
    ) {
        this.enrollments = enrollments;
        this.accountRepository = accountRepository;
        this.classRepository = classRepository;
        this.schoolYearRepository = schoolYearRepository;
        this.accountRoleRepository = accountRoleRepository;
        this.roleRepository = roleRepository;
        this.enrollmentCodeService = enrollmentCodeService;
        this.institutionAccessService = institutionAccessService;
        this.teachingAssignmentService = teachingAssignmentService;
    }

    public Mono<EnrollmentResponse> enroll(
        Long callerAccountId, boolean admin, boolean teacher, EnrollRequest request
    ) {
        Long targetAccountId = request.accountId() != null ? request.accountId() : callerAccountId;
        if (!targetAccountId.equals(callerAccountId) && !admin && !teacher) {
            return Mono.error(ApiException.forbidden("Only ADMIN or TEACHER can enroll another account"));
        }
        boolean selfEnroll = targetAccountId.equals(callerAccountId);

        return Mono.defer(() -> accountRepository.findById(targetAccountId)
                .filter(account -> account.getDeletedAt() == null && Boolean.TRUE.equals(account.getActive()))
                .switchIfEmpty(Mono.error(ApiException.notFound("Account not found"))))
            .then(Mono.defer(() -> requireStudentRole(targetAccountId)))
            .then(Mono.defer(() -> selfEnroll
                ? resolveBySelfEnrollCode(request.code())
                : resolveByStaffRequest(callerAccountId, admin, teacher, request.classId(), request.schoolYearId())))
            .flatMap(resolved -> schoolYearRepository.findByIdAndDeletedAtIsNull(resolved.schoolYearId())
                .switchIfEmpty(Mono.error(ApiException.notFound("School year not found")))
                .then(Mono.defer(() ->
                    enrollments.findFirstByAccountIdAndSchoolYearId(targetAccountId, resolved.schoolYearId())
                        .flatMap(existing -> {
                            if (!existing.isDeleted()) {
                                return Mono.<Enrollment>error(ApiException.conflict(
                                    "Account already has an active enrollment for this school year"));
                            }
                            existing.restore();
                            existing.setClassId(resolved.classId());
                            existing.setEnrollmentCodeId(resolved.enrollmentCodeId());
                            return enrollments.save(existing);
                        })
                        .switchIfEmpty(Mono.defer(() -> enrollments.save(new Enrollment(
                            targetAccountId, resolved.classId(), resolved.schoolYearId(), resolved.enrollmentCodeId()))))
                )))
            .map(EnrollmentService::toResponse);
    }

    /**
     * Redeems {@code code} and resolves it to the class it names, so a self-enroll uses
     * the class the school chose rather than one a student picked themselves (#255).
     */
    private Mono<ResolvedClass> resolveBySelfEnrollCode(String code) {
        return enrollmentCodeService.redeem(code)
            .flatMap(redemption -> classRepository.findByIdAndDeletedAtIsNull(redemption.classId())
                .switchIfEmpty(Mono.error(ApiException.notFound("Class not found")))
                .map(clazz -> new ResolvedClass(clazz.getId(), clazz.getSchoolYearId(), redemption.enrollmentCodeId())));
    }

    /** Resolves a staff-selected class after enforcing the caller's institutional scope. */
    private Mono<ResolvedClass> resolveByStaffRequest(
        Long callerAccountId, boolean admin, boolean teacher, Long classId, Long schoolYearId
    ) {
        if (classId == null || schoolYearId == null) {
            return Mono.error(ApiException.badRequest("Class and school year are required"));
        }
        return classRepository.findByIdAndDeletedAtIsNull(classId)
            .switchIfEmpty(Mono.error(ApiException.notFound("Class not found")))
            .flatMap(clazz -> {
                Mono<Void> authorization = !admin && teacher
                    ? institutionAccessService.requireTeacherAffiliatedToInstitution(
                        callerAccountId, clazz.getInstitutionId())
                    : Mono.empty();
                return authorization.then(Mono.defer(() -> {
                    if (!clazz.getSchoolYearId().equals(schoolYearId)) {
                        return Mono.error(ApiException.badRequest(
                            "Class does not belong to the given school year"));
                    }
                    return Mono.just(new ResolvedClass(clazz.getId(), clazz.getSchoolYearId(), null));
                }));
            });
    }

    /** Where an enrollment lands, whichever path (code or staff request) resolved it. */
    private record ResolvedClass(Long classId, Long schoolYearId, Long enrollmentCodeId) { }

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
     * account's when the caller is ADMIN, or classes taught by a TEACHER.
     */
    public Flux<EnrollmentResponse> findVisible(
        Long callerAccountId, boolean admin, boolean teacher, Long accountId
    ) {
        Long targetAccountId = accountId != null ? accountId : callerAccountId;
        if (!targetAccountId.equals(callerAccountId) && !admin && !teacher) {
            return Flux.error(ApiException.forbidden("Only ADMIN or TEACHER can list another account's enrollments"));
        }
        Flux<EnrollmentResponse> visible = enrollments.findAllByAccountIdAndDeletedAtIsNull(targetAccountId)
            .filterWhen(enrollment -> admin || targetAccountId.equals(callerAccountId) || !teacher
                ? Mono.just(true)
                : teachingAssignmentService.hasAssignedToClass(callerAccountId, enrollment.getClassId()))
            .map(EnrollmentService::toResponse);

        if (!targetAccountId.equals(callerAccountId) && !admin && teacher) {
            return visible.switchIfEmpty(Flux.error(ApiException.notFound("Enrollment not found")));
        }
        return visible;
    }

    /**
     * Every active enrollment in the platform, for the administrator who manages
     * them (#146). Only the controller decides who reaches it.
     */
    public Flux<EnrollmentResponse> findAllActive() {
        return enrollments.findAllByDeletedAtIsNullOrderByIdAsc().map(EnrollmentService::toResponse);
    }

    public Mono<EnrollmentResponse> cancel(
        Long callerAccountId, boolean admin, boolean teacher, Long enrollmentId
    ) {
        return enrollments.findById(enrollmentId)
            .filter(enrollment -> !enrollment.isDeleted())
            .switchIfEmpty(Mono.error(ApiException.notFound("Enrollment not found")))
            .flatMap(enrollment -> {
                if (enrollment.getAccountId().equals(callerAccountId) || admin) {
                    return Mono.just(enrollment);
                }
                if (!teacher) {
                    return Mono.error(ApiException.forbidden("Only ADMIN or TEACHER can cancel another account's enrollment"));
                }
                return teachingAssignmentService.requireAssignedToClass(callerAccountId, enrollment.getClassId())
                    .thenReturn(enrollment);
            })
            .flatMap(enrollment -> {
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
