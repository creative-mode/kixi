package ao.creativemode.kixi.shared.service;

import java.util.Collection;
import java.util.Map;

import reactor.core.publisher.Mono;

/**
 * A person's name, masked, for the rows of a conversation (issue #179).
 *
 * <p>First name and the initial of the last. The feed is a class of people and
 * "um colega da turma" for all of them says nothing about who is talking; the same
 * shape the leaderboard already uses keeps one convention across both.</p>
 *
 * <p>A door because {@code feed} may not reach {@code identity} — the same reason
 * {@link InstitutionNameReader} and {@link SimulationShowcaseReader} exist.</p>
 */
public interface DisplayNameReader {

    /** Empty when the account has no profile, which is not a person and gets no label. */
    Mono<String> maskedNameOf(Long accountId);

    /** A masked name and the stored photo address of one author; either may be null. */
    record Brief(String maskedName, String photo) {}

    /**
     * Many authors in one read: the names and photos of a conversation come with one query for the distinct
     * authors, not one per comment (and, on the client, one profile read per commenter for the photo).
     * Accounts without a profile are simply absent from the map.
     */
    Mono<Map<Long, Brief>> briefsOf(Collection<Long> accountIds);
}
