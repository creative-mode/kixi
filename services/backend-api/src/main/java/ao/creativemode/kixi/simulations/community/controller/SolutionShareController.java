package ao.creativemode.kixi.simulations.community.controller;

import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.simulations.community.dto.ShareSolutionRequest;
import ao.creativemode.kixi.simulations.community.dto.SolutionShareResponse;
import ao.creativemode.kixi.simulations.community.service.SolutionSharingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The consent switch for social learning (issue #119): sharing is an explicit
 * act of the simulation's owner, and taking it back removes the resolution
 * from the community without touching the grade.
 */
@RestController
@RequestMapping("/api/v1/simulations")
@Tag(name = "Solution sharing", description = "Opt-in sharing of finished simulations")
public class SolutionShareController {

    private final SolutionSharingService solutionSharingService;
    private final CurrentAccountService currentAccountService;

    public SolutionShareController(
            SolutionSharingService solutionSharingService,
            CurrentAccountService currentAccountService
    ) {
        this.solutionSharingService = solutionSharingService;
        this.currentAccountService = currentAccountService;
    }

    /**
     * Share the caller's finished simulation: anonymously by default, or under
     * the given display name. Staff cannot share on a student's behalf.
     */
    @PostMapping("/{id}/share")
    @Operation(summary = "Opt in to sharing a finished simulation")
    public Mono<ResponseEntity<SolutionShareResponse>> share(
            @PathVariable Long id,
            @RequestBody(required = false) ShareSolutionRequest request
    ) {
        return currentAccountService.requiredAccountId()
                .flatMap(accountId -> solutionSharingService.share(id, accountId, request))
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    /** Take a share back: the resolution leaves the community, the grade stays. */
    @DeleteMapping("/{id}/share")
    @Operation(summary = "Revoke a solution share")
    public Mono<ResponseEntity<Void>> unshare(@PathVariable Long id) {
        return currentAccountService.requiredAccountId()
                .flatMap(accountId -> solutionSharingService.unshare(id, accountId))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
