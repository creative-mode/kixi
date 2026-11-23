package ao.creativemode.kixi.simulations.dto.leaderboard;

import java.util.List;

/**
 * The caller's ranking visibility, one entry per scope it applies to.
 *
 * <p>{@code scope=global} is not here on purpose: it is governed by the cross-institution
 * consent endpoint, and its peers are always anonymous. This response is about the scopes
 * where a student is named or hidden among people they were seated with.</p>
 *
 * @param preferences one entry for {@code class}, {@code school} and {@code friends}, in
 *                    that order, with the value the server currently holds
 */
public record LeaderboardPreferencesResponse(List<LeaderboardPreference> preferences) {

    /**
     * @param scope             {@code class}, {@code school} or {@code friends}
     * @param rankingVisibility {@code visible}, {@code anonymous} or {@code out}
     */
    public record LeaderboardPreference(String scope, String rankingVisibility) { }
}
