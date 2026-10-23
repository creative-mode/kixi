package ao.creativemode.kixi.feed.dto.postReaction;

import ao.creativemode.kixi.feed.enums.PostReactionType;
import jakarta.validation.constraints.NotNull;

/** Idempotently sets the caller's reaction state; safe to retry after a lost response. */
public record PostReactionSetRequest(
        @NotNull(message = "Reaction type is required") PostReactionType reaction,
        @NotNull(message = "Reaction state is required") Boolean reacted
) {}
