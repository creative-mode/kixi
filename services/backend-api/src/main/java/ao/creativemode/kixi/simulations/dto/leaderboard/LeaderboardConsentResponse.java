package ao.creativemode.kixi.simulations.dto.leaderboard;

/**
 * Whether the student agreed to be ranked against students from other schools.
 *
 * <p>{@code crossInstitution} is the current answer, not the answer that was asked for:
 * the client renders what the server holds, so a write that did not land cannot be
 * shown as a preference the student does not have.</p>
 *
 * @param crossInstitution true when the student is in the cross-institution table
 */
public record LeaderboardConsentResponse(boolean crossInstitution) { }
