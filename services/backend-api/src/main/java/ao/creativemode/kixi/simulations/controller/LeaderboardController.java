package ao.creativemode.kixi.simulations.controller;

import ao.creativemode.kixi.shared.service.CurrentAccountService;
import ao.creativemode.kixi.simulations.dto.leaderboard.LeaderboardResponse;
import ao.creativemode.kixi.simulations.service.LeaderboardService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
     *               enrollment; {@code friends} answers 404 until issue #121 lands
     * @param period {@code all} (default) or {@code month}
     */
    @GetMapping
    public Mono<ResponseEntity<LeaderboardResponse>> leaderboard(
            @RequestParam(defaultValue = "class") String scope,
            @RequestParam(required = false) Long statementId,
            @RequestParam(defaultValue = "all") String period) {
        return currentAccountService.requiredAccountId()
                .flatMap(accountId ->
                        leaderboardService.leaderboard(accountId, scope, statementId, period))
                .map(ResponseEntity::ok);
    }
}