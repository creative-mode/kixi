package ao.creativemode.kixi.moderation.service;

import ao.creativemode.kixi.identity.repository.AccountRepository;
import ao.creativemode.kixi.institutions.repository.InstitutionRepository;
import ao.creativemode.kixi.institutions.repository.FriendshipRepository;
import ao.creativemode.kixi.moderation.dto.BlockResponse;
import ao.creativemode.kixi.moderation.dto.BlockedAccountResponse;
import ao.creativemode.kixi.moderation.model.ModerationBlock;
import ao.creativemode.kixi.shared.service.DisplayNameReader;
import reactor.core.publisher.Flux;
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
    private final FriendshipRepository friendships;
    private final DisplayNameReader displayNames;

    public ModerationService(AccountRepository accounts, InstitutionRepository institutions,
                             ModerationBlockRepository blocks, ModerationReportRepository reports,
                             ApiAbuseRateLimiter rateLimiter, FriendshipRepository friendships,
                             DisplayNameReader displayNames) {
        this.friendships = friendships;
        this.displayNames = displayNames;
        this.accounts = accounts;
        this.institutions = institutions;
        this.blocks = blocks;
        this.reports = reports;
        this.rateLimiter = rateLimiter;
    }

    public Mono<BlockResponse> blockState(Long callerId, Long targetId) {
        return requireAccount(targetId).then(pair(callerId, targetId)
                .flatMap(p -> blocks.findByAccountLowAndAccountHigh(p[0], p[1])
                        .map(block -> new BlockResponse(true, blockedBy(block, callerId)))
                        .defaultIfEmpty(new BlockResponse(false, false))));
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

    @Override
    public Mono<Boolean> blockedByViewer(Long viewerAccountId, Long otherAccountId) {
        if (viewerAccountId.equals(otherAccountId)) {
            return Mono.just(false);
        }
        return blocks.findByAccountLowAndAccountHigh(
                        Math.min(viewerAccountId, otherAccountId), Math.max(viewerAccountId, otherAccountId))
                .map(block -> blockedBy(block, viewerAccountId))
                .defaultIfEmpty(false);
    }

    /** Explicit block (idempotent). A pair already blocked keeps its first blocker. */
    public Mono<BlockResponse> block(Long callerId, Long targetId) {
        if (callerId.equals(targetId)) {
            return Mono.error(ApiException.badRequest("You cannot block yourself"));
        }
        return requireAccount(targetId).then(pair(callerId, targetId)
                .flatMap(p -> blocks.insertPair(p[0], p[1], callerId)
                        .onErrorMap(DataIntegrityViolationException.class,
                                e -> ApiException.conflict("Could not update the block state"))
                        // Blocking ends the friendship: a friend list must not keep somebody the caller shut out.
                        .then(Mono.defer(() -> friendships.findBetween(p[0], p[1])))
                        .filter(friendship -> friendship.getDeletedAt() == null)
                        .flatMap(friendship -> {
                            friendship.softDelete();
                            return friendships.save(friendship);
                        })
                        .then(Mono.defer(() -> blockState(callerId, targetId)))));
    }

    /** Explicit unblock. Only who put the block lifts it; for anyone else it does not exist. */
    public Mono<BlockResponse> unblock(Long callerId, Long targetId) {
        if (callerId.equals(targetId)) {
            return Mono.error(ApiException.badRequest("You cannot block yourself"));
        }
        return requireAccount(targetId).then(pair(callerId, targetId)
                .flatMap(p -> blocks.findByAccountLowAndAccountHigh(p[0], p[1])
                        .flatMap(block -> blockedBy(block, callerId)
                                ? blocks.deletePair(p[0], p[1]).thenReturn(new BlockResponse(false, false))
                                : Mono.<BlockResponse>error(ApiException.notFound("Pessoa não encontrada")))
                        .defaultIfEmpty(new BlockResponse(false, false))));
    }

    /** The old toggle, kept for the clients that send an empty PUT. */
    public Mono<BlockResponse> toggleBlock(Long callerId, Long targetId) {
        if (callerId.equals(targetId)) {
            return Mono.error(ApiException.badRequest("You cannot block yourself"));
        }
        return requireAccount(targetId).then(pair(callerId, targetId)
                .flatMap(p -> blocks.findByAccountLowAndAccountHigh(p[0], p[1])
                        .map(java.util.Optional::of)
                        .defaultIfEmpty(java.util.Optional.empty())
                        .flatMap(existing -> existing.isPresent() ? unblock(callerId, targetId) : block(callerId, targetId))));
    }

    /** The people the caller blocked, so they can find and lift each block. */
    public Flux<BlockedAccountResponse> listBlocked(Long callerId) {
        return blocks.findBlockedBy(callerId).collectList().flatMapMany(rows -> {
            java.util.List<Long> others = rows.stream()
                    .map(row -> row.getAccountLow().equals(callerId) ? row.getAccountHigh() : row.getAccountLow())
                    .toList();
            return displayNames.briefsOf(others).defaultIfEmpty(java.util.Map.of()).flatMapMany(briefs ->
                    Flux.fromIterable(rows).map(row -> {
                        Long other = row.getAccountLow().equals(callerId) ? row.getAccountHigh() : row.getAccountLow();
                        DisplayNameReader.Brief brief = briefs.get(other);
                        return new BlockedAccountResponse(other, brief == null ? null : brief.maskedName(),
                                brief == null ? null : brief.photo(), row.getCreatedAt());
                    }));
        });
    }

    private static boolean blockedBy(ModerationBlock block, Long accountId) {
        return block.getBlockedBy() == null || block.getBlockedBy().equals(accountId);
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
}
