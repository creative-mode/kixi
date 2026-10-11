package ao.creativemode.kixi.analytics.repository;

/**
 * Who is asking. An ADMIN sees every school; anyone else is narrowed to their
 * teaching assignments (see {@link AnalyticsSql}).
 */
public record AnalyticsScope(long accountId, boolean admin) {

    /** {@code Overview.scope}: what the dashboard shows as the reach of the numbers. */
    public String label() {
        return admin ? "ADMIN" : "TEACHER";
    }
}
