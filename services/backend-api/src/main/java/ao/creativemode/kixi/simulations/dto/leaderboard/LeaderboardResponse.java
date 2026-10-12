package ao.creativemode.kixi.simulations.dto.leaderboard;

import java.util.List;

/**
 * A caller's slice of a group's ranking (issue #117).
 *
 * <p>Deliberately relative: it carries the caller's own position and a short window of
 * neighbours, not the whole table. In a group small enough that a full ranking would
 * name who is last, {@code smallGroup} is true and the podium is withheld — see
 * {@code LeaderboardService}.</p>
 *
 * @param scope        which cohort the caller asked for
 * @param groupId      the class or institution behind that cohort
 * @param statementId  the statement everyone sat, or null for every statement
 * @param period       the time window applied, "all" or "month"
 * @param totalStudents how many students belong to the cohort, ranked or not
 * @param rankedStudents how many of them have a score that could be ranked
 * @param smallGroup   true when the cohort is too small to publish a podium
 * @param myPosition   the caller's rank, or null when they have no rankable score
 * @param myAverage    the caller's average, as a percentage
 * @param entries      the neighbours and, in a large group, the podium
 */
public record LeaderboardResponse(
        String scope,
        Long groupId,
        Long statementId,
        String period,
        int totalStudents,
        int rankedStudents,
        boolean smallGroup,
        Integer myPosition,
        Double myAverage,
        List<Entry> entries
) {

    /**
     * One row of the window. Names are masked on the way out: a ranking that named
     * classmates whole would republish data the student never consented to share.
     *
     * @param accountId the caller's own id, and null for everyone else. A colleague's
     *                  account id would turn a masked label back into a person, since
     *                  the same id answers half a dozen other endpoints.
     */
    public record Entry(
            int position,
            Long accountId,
            String displayName,
            Double average,
            boolean me
    ) { }
}