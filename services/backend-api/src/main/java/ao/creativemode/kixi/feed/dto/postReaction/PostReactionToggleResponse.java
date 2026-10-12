package ao.creativemode.kixi.feed.dto.postReaction;

/** What {@code toggleReaction} actually did: added the reaction, or removed it. */
public record PostReactionToggleResponse(boolean reacted) {}
