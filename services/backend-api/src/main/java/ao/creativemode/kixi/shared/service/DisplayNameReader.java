package ao.creativemode.kixi.shared.service;

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
}
