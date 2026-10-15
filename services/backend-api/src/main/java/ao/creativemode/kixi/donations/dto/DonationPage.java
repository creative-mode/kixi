package ao.creativemode.kixi.donations.dto;

import java.util.List;
import java.util.Map;

/** A page of the review list, with the count of every status for the filter chips. */
public record DonationPage(List<DonationSummary> items, long total, int page, int limit, Map<String, Long> counts) { }
