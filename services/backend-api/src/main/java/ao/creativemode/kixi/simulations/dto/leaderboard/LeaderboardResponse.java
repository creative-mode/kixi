package ao.creativemode.kixi.simulations.dto.leaderboard;

import java.util.List;

/**
 * A caller's slice of a group's ranking (issue #117, extended by #189).
 *
 * <p>Deliberately relative: it carries the caller's own position and a short window of
 * neighbours, not the whole table. In a group small enough that a full ranking would
 * name who is last, {@code smallGroup} is true and the podium is withheld — see
 * {@code LeaderboardService}.</p>
 *
 * @param scope        which cohort the caller asked for
 * @param groupId      the class or institution behind that cohort, and null for
 *                     {@code global}, which has no group behind it
 * @param statementId  the statement everyone sat, or null for every statement
 * @param period       the time window applied, "all" or "month"
 * @param totalStudents how many students belong to the cohort, ranked or not
 * @param rankedStudents how many of them have a score that could be ranked
 * @param institutionsCounted how many schools the comparison drew from. Only
 *                     {@code scope=global} compares across schools, so this is 0
 *                     everywhere else — it is the number that lets a client tell the
 *                     student where their place came from
 * @param smallGroup   true when the cohort is too small to publish a podium
 * @param myPosition   the caller's rank, or null when they have no rankable score
 * @param myAverage    the caller's average, as a percentage
 * @param myPercentile where the caller stands inside their own institution, 0 to 100,
 *                     and null outside {@code scope=global}
 * @param entries      the neighbours and, in a large group, the podium
 */
public record LeaderboardResponse(
        String scope,
        Long groupId,
        Long statementId,
        String period,
        String metric,
        int totalStudents,
        int rankedStudents,
        int institutionsCounted,
        boolean smallGroup,
        Integer myPosition,
        Double myAverage,
        Double myPercentile,
        List<Entry> entries
) {

    /**
     * One row of the window. Names are masked on the way out: a ranking that named
     * classmates whole would republish data the student never consented to share.
     *
     * <p>In {@code scope=global} the masking stops being enough and the name goes
     * altogether — a first name and an initial is still a name, and out there it would
     * be read against a school the response deliberately does not carry. Those rows show
     * a neutral label instead (ADR-0013).</p>
     *
     * @param accountId the caller's own id, and null for everyone else. A colleague's
     *                  account id would turn a masked label back into a person, since
     *                  the same id answers half a dozen other endpoints.
     * @param percentile where this student stands inside their own institution, or null
     *                   in the scopes that have no institution to stand inside
     * @param xp         lifetime XP, which is what {@code metric=xp} ordered by, and null
     *                   for an account that has not been rewarded yet
     */
    public record Entry(
            int position,
            Long accountId,
            String displayName,
            Double average,
            Double percentile,
            Long xp,
            boolean me
    ) { }
}
