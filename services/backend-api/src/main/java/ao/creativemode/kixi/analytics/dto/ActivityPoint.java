package ao.creativemode.kixi.analytics.dto;

/** {@code ActivityPoint}: finished simulations of one UTC day. {@code date} is yyyy-MM-dd. */
public record ActivityPoint(String date, long finished, Double averageScorePercent) {
}
