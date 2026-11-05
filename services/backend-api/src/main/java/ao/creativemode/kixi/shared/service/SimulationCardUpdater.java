package ao.creativemode.kixi.shared.service;

import reactor.core.publisher.Mono;

/**
 * Brings the figures on a published simulation card back in line with the
 * attempt it was made from (kixi#464).
 *
 * <p>The card is a snapshot taken on the server when it is published, but the
 * final score is not fixed: correcting an open answer rewrites it. Without
 * this door the feed would keep showing the score the attempt had before the
 * teacher corrected it. Only the mutable figures are refreshed - the number of
 * correct answers, the question count and the time spent do not change, and
 * the school's name is still the one read at publish time.</p>
 *
 * <p>A door rather than a direct call, like {@link SimulationShowcaseReader}:
 * the feed may not be reached from simulations, so the implementation lives in
 * the feed and the interface here in shared. A correction that is not
 * published anywhere is not an error - it simply updates no rows.</p>
 */
public interface SimulationCardUpdater {

    /**
     * Updates the published card of {@code simulationId}, if there is one.
     * Idempotent: running it twice with the same figures changes the same row
     * to the same values.
     *
     * @param finalScore         the attempt's current final score
     * @param pendingReviewCount how many open answers are still awaiting
     *                           correction, so the card can say the score is
     *                           provisional
     */
    Mono<Void> refresh(Long simulationId, double finalScore, int pendingReviewCount);
}
