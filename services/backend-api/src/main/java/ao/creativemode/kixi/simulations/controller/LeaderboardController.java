package ao.creativemode.kixi.simulations.controller;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.simulations.dto.leaderboard.LeaderboardConsentRequest;
import ao.creativemode.kixi.simulations.dto.leaderboard.LeaderboardConsentResponse;
import ao.creativemode.kixi.simulations.dto.leaderboard.LeaderboardResponse;
import ao.creativemode.kixi.simulations.service.LeaderboardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * The relative ranking of issue #117.
 *
 * <p>Every signed-in account may read it, and every one of them only ever sees the
 * cohort {@code /me} resolves for them: the cohort is never a parameter, so there is no
 * combination of query values that reaches somebody else's group.</p>
 */
@RestController
@RequestMapping("/api/v1/leaderboard")
@Tag(name = "Leaderboard", description = "Relative ranking inside a cohort, and across institutions")
public class LeaderboardController {

    private final CurrentAccountService currentAccountService;
    private final LeaderboardService leaderboardService;

    public LeaderboardController(
            CurrentAccountService currentAccountService,
            LeaderboardService leaderboardService) {
        this.currentAccountService = currentAccountService;
        this.leaderboardService = leaderboardService;
    }

    /**
     * @param scope  {@code class} or {@code school}, both taken from the caller's own
     *               enrollment; {@code global} compares the caller with students from other
     *               schools and is refused with 403 until the caller has consented here;
     *               {@code friends} ranks an accepted friendship list
     * @param period {@code all} (default) or {@code month}
     */
    @GetMapping
    public Mono<ResponseEntity<LeaderboardResponse>> leaderboard(
            @RequestParam(defaultValue = "class") String scope,
            @RequestParam(required = false) Long statementId,
            @RequestParam(defaultValue = "all") String period,
            @RequestParam(defaultValue = "average") String metric) {
        return currentAccountService.requiredAccountId()
                .flatMap(accountId ->
                        leaderboardService.leaderboard(accountId, scope, statementId, period, metric))
                .map(ResponseEntity::ok);
    }

    /**
     * Give or take back consent to be ranked against students from other schools.
     *
     * <p>Opt-in, so a student who has never been asked is not in the cross-institution
     * table — and, symmetrically, is not served an empty one: asking for {@code global}
     * without this returns 403 so the client can offer the switch rather than show a
     * blank page (ADR-0013, decision 1).</p>
     *
     * <p>The body is not optional and the flag is not nullable. A missing field would
     * otherwise be a {@code null} Boolean unboxed on the way into the database, and the
     * student would be told they had answered a question they were never asked.</p>
     */
    @PutMapping("/consent")
    @Operation(summary = "Allow or forbid being ranked across institutions")
    public Mono<ResponseEntity<LeaderboardConsentResponse>> consent(
            @RequestBody(required = false) LeaderboardConsentRequest request) {
        if (request == null || request.crossInstitution() == null) {
            return Mono.error(ApiException.badRequest(
                    "crossInstitution is required: true to be ranked across institutions"));
        }
        return currentAccountService.requiredAccountId()
                .flatMap(accountId -> leaderboardService.consent(accountId, request.crossInstitution()))
                .map(ResponseEntity::ok);
    }
}
