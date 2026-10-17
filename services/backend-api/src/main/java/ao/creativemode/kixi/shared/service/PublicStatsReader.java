package ao.creativemode.kixi.shared.service;

import java.util.List;
import reactor.core.publisher.Mono;

/**
 * What of an account's progress may be shown on its public profile (issue #336). A port in
 * {@code shared}, like {@link FeedScopeAuthorizer}: institutions asks for it and gamification
 * answers, so neither module depends on the other.
 */
public interface PublicStatsReader {

    Mono<PublicStats> read(Long accountId);

    record PublicStats(int level, long xp, List<String> badges) {

        public static PublicStats none() {
            return new PublicStats(1, 0, List.of());
        }
    }
}
