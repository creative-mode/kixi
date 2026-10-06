package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.academic.repository.ClassRepository;
import ao.creativemode.kixi.academic.repository.SchoolYearRepository;
import ao.creativemode.kixi.identity.repository.AccountRepository;
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
 */
@Service
public class EnrollmentService {

    private final EnrollmentRepository enrollments;
    private final AccountRepository accountRepository;
    private final ClassRepository classRepository;
    private final SchoolYearRepository schoolYearRepository;

    public EnrollmentService(
        EnrollmentRepository enrollments,
        AccountRepository accountRepository,
        ClassRepository classRepository,
        SchoolYearRepository schoolYearRepository
    ) {
        this.enrollments = enrollments;
        this.accountRepository = accountRepository;
        this.classRepository = classRepository;
        this.schoolYearRepository = schoolYearRepository;
    }

    public Mono<EnrollmentResponse> enroll(Long callerAccountId, boolean staff, EnrollRequest request) {
        Long targetAccountId = request.accountId() != null ? request.accountId() : callerAccountId;
        if (!targetAccountId.equals(callerAccountId) && !staff) {
            return Mono.error(ApiException.forbidden("Only ADMIN or TEACHER can enroll another account"));
        }

        return Mono.defer(() -> accountRepository.findById(targetAccountId)
                .filter(account -> account.getDeletedAt() == null && Boolean.TRUE.equals(account.getActive()))
                .switchIfEmpty(Mono.error(ApiException.notFound("Account not found"))))
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
