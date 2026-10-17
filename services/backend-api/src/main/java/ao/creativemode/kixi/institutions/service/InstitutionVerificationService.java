package ao.creativemode.kixi.institutions.service;

import ao.creativemode.kixi.identity.repository.TeacherRepository;
import ao.creativemode.kixi.institutions.dto.verification.VerificationRequestCreate;
import ao.creativemode.kixi.institutions.dto.verification.VerificationRequestResponse;
import ao.creativemode.kixi.institutions.model.Institution;
import ao.creativemode.kixi.institutions.model.InstitutionVerificationRequest;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionTeacherRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionVerificationRequestRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The verification badge of a school (issue #332).
 *
 * <p>A school asks, an administrator decides. Nobody verifies their own school: the request only
 * records what was declared, and the badge is set by {@link #approve}, which only an administrator
 * reaches (enforced in {@code SecurityConfig}).</p>
 */
@Service
public class InstitutionVerificationService {

    private final InstitutionRepository institutions;
    private final InstitutionVerificationRequestRepository requests;
    private final InstitutionTeacherRepository teacherLinks;
    private final TeacherRepository teachers;

    public InstitutionVerificationService(
        InstitutionRepository institutions,
        InstitutionVerificationRequestRepository requests,
        InstitutionTeacherRepository teacherLinks,
        TeacherRepository teachers
    ) {
        this.institutions = institutions;
        this.requests = requests;
        this.teacherLinks = teacherLinks;
        this.teachers = teachers;
    }

    /** An administrator, or a teacher affiliated with the school, asks for the badge. */
    public Mono<VerificationRequestResponse> submit(
        Long accountId, boolean admin, Long institutionId, VerificationRequestCreate data
    ) {
        return institutions
            .findByIdAndDeletedAtIsNull(institutionId)
            .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found")))
            .flatMap(institution -> institution.isVerified()
                ? Mono.<Institution>error(ApiException.conflict("This institution is already verified"))
                : Mono.just(institution))
            .flatMap(institution -> requireAffiliation(accountId, admin, institutionId).thenReturn(institution))
            .flatMap(institution -> requests
                .existsByInstitutionIdAndStatus(institutionId, InstitutionVerificationRequest.PENDING)
                .flatMap(pending -> pending
                    ? Mono.<InstitutionVerificationRequest>error(
                        ApiException.conflict("A verification request is already waiting for review"))
                    : requests.save(InstitutionVerificationRequest.builder()
                        .institutionId(institutionId)
                        .officialName(data.officialName().trim())
                        .registrationNumber(data.registrationNumber().trim())
                        .contactEmail(data.contactEmail().trim())
                        .documentUrl(blankToNull(data.documentUrl()))
                        .notes(blankToNull(data.notes()))
                        .status(InstitutionVerificationRequest.PENDING)
                        .requestedByAccountId(accountId)
                        .createdAt(LocalDateTime.now())
                        .build())))
            .map(InstitutionVerificationService::toResponse);
    }

    public Flux<VerificationRequestResponse> list(String status) {
        Flux<InstitutionVerificationRequest> rows = status == null || status.isBlank()
            ? requests.findAllByOrderByCreatedAtDesc()
            : requests.findAllByStatusOrderByCreatedAtDesc(status.trim().toLowerCase());
        return rows.map(InstitutionVerificationService::toResponse);
    }

    @Transactional
    public Mono<VerificationRequestResponse> approve(Long requestId, Long adminAccountId, String note) {
        return pendingRequest(requestId).flatMap(request ->
            institutions
                .findByIdAndDeletedAtIsNull(request.getInstitutionId())
                .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found")))
                .flatMap(institution -> {
                    LocalDateTime now = LocalDateTime.now();
                    institution.setVerified(true);
                    institution.setVerifiedAt(now);
                    institution.setVerifiedByAccountId(adminAccountId);
                    return institutions.save(institution);
                })
                .then(Mono.defer(() -> requests.save(decided(
                    request, InstitutionVerificationRequest.APPROVED, adminAccountId, blankToNull(note))))))
            .map(InstitutionVerificationService::toResponse);
    }

    public Mono<VerificationRequestResponse> reject(Long requestId, Long adminAccountId, String note) {
        if (note == null || note.isBlank()) {
            return Mono.error(ApiException.badRequest("A reason is required to reject a verification request"));
        }
        return pendingRequest(requestId)
            .flatMap(request -> requests.save(decided(
                request, InstitutionVerificationRequest.REJECTED, adminAccountId, note.trim())))
            .map(InstitutionVerificationService::toResponse);
    }

    /** Only an administrator takes the badge away; it disappears at once. */
    public Mono<Void> revoke(Long institutionId) {
        return institutions
            .findByIdAndDeletedAtIsNull(institutionId)
            .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found")))
            .flatMap(institution -> {
                if (!institution.isVerified()) {
                    return Mono.<Institution>error(ApiException.conflict("This institution is not verified"));
                }
                institution.setVerified(false);
                institution.setVerifiedAt(null);
                institution.setVerifiedByAccountId(null);
                return institutions.save(institution);
            })
            .then();
    }

    private Mono<InstitutionVerificationRequest> pendingRequest(Long requestId) {
        return requests
            .findById(requestId)
            .switchIfEmpty(Mono.error(ApiException.notFound("Verification request not found")))
            .flatMap(request -> InstitutionVerificationRequest.PENDING.equals(request.getStatus())
                ? Mono.just(request)
                : Mono.<InstitutionVerificationRequest>error(
                    ApiException.conflict("This verification request was already decided")));
    }

    private Mono<Void> requireAffiliation(Long accountId, boolean admin, Long institutionId) {
        if (admin) {
            return Mono.empty();
        }
        return teachers
            .findByAccountIdAndDeletedAtIsNull(accountId)
            .switchIfEmpty(Mono.error(ApiException.forbidden("Only teachers of the institution can ask for verification")))
            .flatMap(teacher ->
                teacherLinks.existsByInstitutionIdAndTeacherIdAndDeletedAtIsNull(institutionId, teacher.getId()))
            .filter(Boolean::booleanValue)
            .switchIfEmpty(Mono.error(ApiException.forbidden("Teacher is not affiliated with this institution")))
            .then();
    }

    private static InstitutionVerificationRequest decided(
        InstitutionVerificationRequest request, String status, Long adminAccountId, String note
    ) {
        request.setStatus(status);
        request.setReviewNote(note);
        request.setReviewedByAccountId(adminAccountId);
        request.setReviewedAt(LocalDateTime.now());
        return request;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static VerificationRequestResponse toResponse(InstitutionVerificationRequest r) {
        return new VerificationRequestResponse(
            r.getId(),
            r.getInstitutionId(),
            r.getOfficialName(),
            r.getRegistrationNumber(),
            r.getContactEmail(),
            r.getDocumentUrl(),
            r.getNotes(),
            r.getStatus(),
            r.getReviewNote(),
            r.getReviewedAt(),
            r.getCreatedAt()
        );
    }
}
