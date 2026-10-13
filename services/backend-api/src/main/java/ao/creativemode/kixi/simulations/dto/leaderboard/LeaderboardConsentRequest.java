package ao.creativemode.kixi.simulations.dto.leaderboard;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The student's answer about being ranked across institutions.
 *
 * <p>A body rather than two endpoints, because "opt in" and "opt out" are one field and
 * two paths is one more thing to keep symmetric. Staff cannot answer on a student's
 * behalf: the endpoint resolves the caller and takes nothing else.</p>
 *
 * @param crossInstitution true to be ranked against other schools, false to be removed
 *                         from the table. Absence means the student has never been asked
 *                         and is treated the same as false
 */
public record LeaderboardConsentRequest(@JsonProperty("crossInstitution") Boolean crossInstitution) { }
