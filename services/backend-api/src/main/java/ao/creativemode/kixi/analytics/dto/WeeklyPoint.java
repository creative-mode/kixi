package ao.creativemode.kixi.analytics.dto;

/** Finished simulations of one week (Monday, UTC, yyyy-MM-dd) of a class. */
public record WeeklyPoint(String weekStart, long finished, Double averageScorePercent) {
}
