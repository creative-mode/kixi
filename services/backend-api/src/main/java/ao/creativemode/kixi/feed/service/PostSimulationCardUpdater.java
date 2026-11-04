package ao.creativemode.kixi.feed.service;

import ao.creativemode.kixi.feed.repository.PostFeedRepository;
import ao.creativemode.kixi.shared.service.SimulationCardUpdater;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * The feed's side of {@link SimulationCardUpdater}: a published simulation card
 * lives in {@code posts}, and this rewrites the only two figures that can move
 * after publication - the final score and the number of answers still pending
 * correction (kixi#464).
 */
@Service
public class PostSimulationCardUpdater implements SimulationCardUpdater {

    private final PostFeedRepository feedRepository;

    public PostSimulationCardUpdater(PostFeedRepository feedRepository) {
        this.feedRepository = feedRepository;
    }

    @Override
    public Mono<Void> refresh(Long simulationId, double finalScore, int pendingReviewCount) {
        return feedRepository.updateShowcase(simulationId, finalScore, pendingReviewCount);
    }
}
