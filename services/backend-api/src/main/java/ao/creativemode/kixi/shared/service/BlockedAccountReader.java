package ao.creativemode.kixi.shared.service;

import reactor.core.publisher.Mono;

/** Reads the symmetric account block relation without coupling profile code to moderation. */
public interface BlockedAccountReader {
    Mono<Boolean> blockedBetween(Long firstAccountId, Long secondAccountId);

    /**
     * Whether a block exists between the two accounts and {@code viewerAccountId} is the one who
     * put it (or the row predates the blocker column, when either side may lift it). The blocker
     * keeps a way back to the other person's profile; the blocked person does not.
     */
    Mono<Boolean> blockedByViewer(Long viewerAccountId, Long otherAccountId);
}
