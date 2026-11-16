package ao.creativemode.kixi.simulations.dto.leaderboard;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * How the caller wants to appear inside one scope of the ranking (#611).
 *
 * @param scope      {@code class}, {@code school}, {@code friends} or {@code global}; the
 *                   same vocabulary the ranking takes, never a group id — a scope that
 *                   could name another student's group would be an IDOR
 * @param preference {@code visible}, {@code anonymous} («Um colega») or
 *                   {@code hidden} (out of the table)
 */
public record LeaderboardVisibilityRequest(
        @JsonProperty("scope") String scope,
        @JsonProperty("preference") String preference) { }
