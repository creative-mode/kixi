package ao.creativemode.kixi.moderation.dto;

/** O corpo de {@code POST /moderation/reports/{id}/(ignore|hide|suspend)}. */
public record DecisionRequest(String note) {}
