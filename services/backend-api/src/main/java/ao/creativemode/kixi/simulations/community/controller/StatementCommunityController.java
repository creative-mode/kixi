package ao.creativemode.kixi.simulations.community.controller;

import ao.creativemode.kixi.shared.dto.PageResponse;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.simulations.community.dto.CommunityStatsResponse;
import ao.creativemode.kixi.simulations.community.dto.SharedSolutionResponse;
import ao.creativemode.kixi.simulations.community.service.CommunityStatsService;
import ao.creativemode.kixi.simulations.community.service.SolutionSharingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The community side of one statement (issue #119): how everybody did on the
 * same paper, and the resolutions students chose to share.
 *
 * <p>Thin by design, like {@code StatementCatalogController}: the account
 * comes from the JWT and everything else lives in the services. GET under
 * {@code /api/v1/statements} is open to any authenticated account in
 * SecurityConfig, so no extra rule is needed.
 */
@RestController
@RequestMapping("/api/v1/statements")
@Tag(name = "Statement community", description = "Community performance and shared resolutions per statement")
public class StatementCommunityController {

    private final CommunityStatsService communityStatsService;
    private final SolutionSharingService solutionSharingService;
    private final CurrentAccountService currentAccountService;

    public StatementCommunityController(
            CommunityStatsService communityStatsService,
            SolutionSharingService solutionSharingService,
            CurrentAccountService currentAccountService
    ) {
        this.communityStatsService = communityStatsService;
        this.solutionSharingService = solutionSharingService;
        this.currentAccountService = currentAccountService;
    }

    /**
     * Grade distribution, per-question hit rate, average time — across
     * schools — with the caller's own attempt for the comparison.
     */
    @GetMapping("/{id}/community")
    @Operation(summary = "Community performance on a statement, with the caller's attempt")
    public Mono<ResponseEntity<CommunityStatsResponse>> community(@PathVariable Long id) {
        return currentAccountService.requiredAccountId()
                .flatMap(accountId -> communityStatsService.findCommunity(id, accountId))
                .map(ResponseEntity::ok);
    }

    /**
     * Resolutions students opted in to share, newest first. Only consented
     * shares appear; {@code institutionId} narrows the listing to one school
     * while the aggregate above always spans them.
     */
    @GetMapping("/{id}/community/solutions")
    @Operation(summary = "Shared resolutions for a statement, optionally filtered by school")
    public Mono<ResponseEntity<PageResponse<SharedSolutionResponse>>> solutions(
            @PathVariable Long id,
            @RequestParam(required = false) Long institutionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return currentAccountService.requiredAccountId()
                .flatMap(accountId -> solutionSharingService.listSolutions(
                        id, institutionId, page, size, accountId))
                .map(ResponseEntity::ok);
    }
}
