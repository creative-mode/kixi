package ao.creativemode.kixi.moderation.dto;

/** Optional body of {@code PUT .../block}: absent or empty keeps the old toggle; a value is an explicit state. */
public record BlockRequest(Boolean blocked) { }
