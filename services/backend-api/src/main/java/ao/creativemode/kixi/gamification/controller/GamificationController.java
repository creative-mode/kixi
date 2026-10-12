package ao.creativemode.kixi.gamification.controller;

import ao.creativemode.kixi.gamification.dto.UserBadgeResponse;
import ao.creativemode.kixi.gamification.dto.UserStatsResponse;
import ao.creativemode.kixi.gamification.service.GamificationService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/me")
public class GamificationController {
    private final GamificationService gamification;
    private final CurrentAccountService currentAccount;

    public GamificationController(GamificationService gamification, CurrentAccountService currentAccount) {
        this.gamification = gamification;
        this.currentAccount = currentAccount;
    }

    @GetMapping("/stats")
    public Mono<ResponseEntity<UserStatsResponse>> getStats() {
        return currentAccount.requiredAccountId()
            .flatMap(gamification::getStats)
            .map(ResponseEntity::ok);
    }

    @GetMapping("/badges")
    public Mono<ResponseEntity<List<UserBadgeResponse>>> getBadges() {
        return currentAccount.requiredAccountId()
            .flatMapMany(gamification::getBadges)
            .collectList()
            .map(ResponseEntity::ok);
    }
}
