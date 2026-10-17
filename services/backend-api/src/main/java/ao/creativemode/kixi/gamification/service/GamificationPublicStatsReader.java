package ao.creativemode.kixi.gamification.service;

import ao.creativemode.kixi.gamification.dto.UserBadgeResponse;
import ao.creativemode.kixi.shared.service.PublicStatsReader;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Level, points and the latest badges of an account, for its public profile (issue #336). */
@Service
public class GamificationPublicStatsReader implements PublicStatsReader {

    static final int MAX_BADGES = 12;

    private final GamificationService gamification;

    public GamificationPublicStatsReader(GamificationService gamification) {
        this.gamification = gamification;
    }

    @Override
    public Mono<PublicStats> read(Long accountId) {
        Mono<java.util.List<String>> badges = gamification
            .getBadges(accountId)
            .map(UserBadgeResponse::name)
            .take(MAX_BADGES)
            .collectList();
        return Mono.zip(gamification.getStats(accountId), badges)
            .map(parts -> new PublicStats(
                parts.getT1().level() == null ? 1 : parts.getT1().level(),
                parts.getT1().xp() == null ? 0 : parts.getT1().xp(),
                parts.getT2()))
            .defaultIfEmpty(PublicStats.none());
    }
}
