package ao.creativemode.kixi.analytics.repository;

/**
 * Who is asking, and what they narrowed the view to. An ADMIN sees every school;
 * anyone else is narrowed to their teaching assignments (see {@link AnalyticsSql}).
 * {@code institutionId} and {@code classId} are optional filters, 0 for "all": they
 * can only narrow what the role already allows.
 */
public record AnalyticsScope(long accountId, boolean admin, long institutionId, long classId) {

    public AnalyticsScope(long accountId, boolean admin) {
        this(accountId, admin, 0L, 0L);
    }

    /** {@code Overview.scope}: what the dashboard shows as the reach of the numbers. */
    public String label() {
        return admin ? "ADMIN" : "TEACHER";
    }

    /** An ADMIN with no filter: school-wide counts apply. */
    public boolean wide() {
        return admin && institutionId == 0 && classId == 0;
    }

    public AnalyticsScope filtered(Long institution, Long klass) {
        return new AnalyticsScope(accountId, admin,
                institution == null ? 0L : institution, klass == null ? 0L : klass);
    }
}
