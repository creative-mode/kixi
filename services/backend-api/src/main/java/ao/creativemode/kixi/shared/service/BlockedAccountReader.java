package ao.creativemode.kixi.shared.service;

import reactor.core.publisher.Mono;

/** Reads the symmetric account block relation without coupling profile code to moderation. */
public interface BlockedAccountReader {
    Mono<Boolean> blockedBetween(Long firstAccountId, Long secondAccountId);
}
