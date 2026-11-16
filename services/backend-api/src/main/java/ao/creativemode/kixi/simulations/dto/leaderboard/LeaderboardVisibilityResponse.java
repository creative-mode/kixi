package ao.creativemode.kixi.simulations.dto.leaderboard;

/**
 * The caller's visibility for one scope.
 *
 * <p>{@code preference} is the row the server holds, not the row the client asked for:
 * a write that did not land cannot be shown as a choice the student did not make.
 * Absence of a row is reported as {@code visible}.</p>
 *
 * @param scope      the scope these values belong to
 * @param preference {@code visible}, {@code anonymous} or {@code hidden}
 */
public record LeaderboardVisibilityResponse(String scope, String preference) { }
