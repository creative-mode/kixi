package ao.creativemode.kixi.moderation.service;

import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.moderation.dto.BlockResponse;
import ao.creativemode.kixi.moderation.dto.ReportRequest;
import ao.creativemode.kixi.moderation.dto.ReportResponse;
import ao.creativemode.kixi.moderation.model.ModerationReport;
import ao.creativemode.kixi.moderation.repository.ModerationBlockRepository;
import ao.creativemode.kixi.moderation.repository.ModerationReportRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.BlockedAccountReader;
import ao.creativemode.kixi.shared.service.ApiAbuseRateLimiter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Set;

@Service
public class ModerationService implements BlockedAccountReader {
    private static final Set<String> REASONS = Set.of(
            "IMPERSONATION", "FAKE_SCHOOL", "OFFENSIVE_NAME", "OFFENSIVE_PHOTO",
            "MISLEADING_CONTENT", "BLOCK", "OTHER");

    private final AccountRepository accounts;
    private final InstitutionRepository institutions;
    private final ModerationBlockRepository blocks;
    private final ModerationReportRepository reports;
    private final ApiAbuseRateLimiter rateLimiter;

    public ModerationService(AccountRepository accounts, InstitutionRepository institutions,
                             ModerationBlockRepository blocks, ModerationReportRepository reports,
                             ApiAbuseRateLimiter rateLimiter) {
        this.accounts = accounts;
        this.institutions = institutions;
        this.blocks = blocks;
        this.reports = reports;
        this.rateLimiter = rateLimiter;
    }

    public Mono<BlockResponse> blockState(Long callerId, Long targetId) {
        return requireAccount(targetId).then(pair(callerId, targetId)
                .flatMap(p -> blocks.existsPair(p[0], p[1])))
                .map(BlockResponse::new);
    }

    @Override
    public Mono<Boolean> blockedBetween(Long firstAccountId, Long secondAccountId) {
        if (firstAccountId.equals(secondAccountId)) {
            return Mono.just(false);
        }
        Long low = Math.min(firstAccountId, secondAccountId);
        Long high = Math.max(firstAccountId, secondAccountId);
        return blocks.existsPair(low, high);
    }

    public Mono<BlockResponse> toggleBlock(Long callerId, Long targetId) {
        if (callerId.equals(targetId)) {
            return Mono.error(ApiException.badRequest("You cannot block yourself"));
        }
        return requireAccount(targetId).then(pair(callerId, targetId)
                .flatMap(p -> atomicToggle(p[0], p[1])));
    }

    public Mono<ReportResponse> reportAccount(Long callerId, Long targetId, ReportRequest request) {
        return rateLimiter.checkReport(callerId)
                .then(requireAccount(targetId))
                .then(validate(callerId, targetId, "ACCOUNT", request))
                .flatMap(valid -> saveReport(callerId, targetId, null, "ACCOUNT", valid));
    }

    public Mono<ReportResponse> reportInstitution(Long callerId, Long targetId, ReportRequest request) {
        return rateLimiter.checkReport(callerId)
                .then(institutions.findByIdAndDeletedAtIsNull(targetId)
                        .switchIfEmpty(Mono.error(ApiException.notFound("Institution not found"))))
                .then(validate(callerId, targetId, "INSTITUTION", request))
                .flatMap(valid -> saveReport(callerId, null, targetId, "INSTITUTION", valid));
    }

    private Mono<ReportRequest> validate(Long callerId, Long targetId, String target, ReportRequest request) {
        if (request == null || request.reason() == null) {
            return Mono.error(ApiException.badRequest("Choose a report reason"));
        }
        String reason = request.reason().trim();
        String note = request.note() == null ? "" : request.note().trim();
        if (!REASONS.contains(reason)) return Mono.error(ApiException.badRequest("Choose a valid report reason"));
        if (note.length() > 500) return Mono.error(ApiException.badRequest("The note must not exceed 500 characters"));
        if ("OTHER".equals(reason) && note.isBlank()) return Mono.error(ApiException.badRequest("Write the reason"));
        if ("BLOCK".equals(reason) && "INSTITUTION".equals(target)) {
            return Mono.error(ApiException.badRequest("This reason is only valid for an account"));
        }
        if ("ACCOUNT".equals(target) && callerId.equals(targetId)) {
            return Mono.error(ApiException.badRequest("You cannot report yourself"));
        }
        return Mono.just(new ReportRequest(reason, note));
    }

    private Mono<ReportResponse> saveReport(Long callerId, Long accountId, Long institutionId,
                                             String target, ReportRequest request) {
        ModerationReport report = new ModerationReport();
        report.setTargetType(target);
        report.setTargetAccountId(accountId);
        report.setTargetInstitutionId(institutionId);
        report.setReporterAccountId(callerId);
        report.setReason(request.reason());
        report.setNote(request.note());
        report.setCreatedAt(LocalDateTime.now());
        return reports.save(report)
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> ApiException.conflict("Could not save the report"))
                .map(saved -> new ReportResponse(saved.getId(), saved.getTargetType(),
                        accountId != null ? accountId : institutionId, saved.getReporterAccountId(),
                        saved.getReason(), saved.getNote(), saved.getCreatedAt()));
    }

    private Mono<Void> requireAccount(Long id) {
        return accounts.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Profile not found")))
                .then();
    }

    private Mono<Long[]> pair(Long a, Long b) {
        return Mono.just(a < b ? new Long[]{a, b} : new Long[]{b, a});
    }

    private Mono<BlockResponse> atomicToggle(Long low, Long high) {
        return blocks.togglePair(low, high)
                .defaultIfEmpty(false)
                .map(BlockResponse::new);
    }
}
