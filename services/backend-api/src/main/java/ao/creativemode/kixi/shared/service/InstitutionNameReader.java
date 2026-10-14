package ao.creativemode.kixi.shared.service;

import reactor.core.publisher.Mono;

/** The name of a school, for the feed card that carries it (issue #238). A door for the same reason as the others. */
public interface InstitutionNameReader {

    /** Empty when the school is unknown, so a card falls back to no name rather than failing. */
    Mono<String> nameOf(Long institutionId);
}
