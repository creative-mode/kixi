package ao.creativemode.kixi.feed.dto.postReaction;

import ao.creativemode.kixi.feed.enums.PostReactionType;
import jakarta.validation.constraints.NotNull;

public record PostReactionRequest(
        @NotNull(message = "Reaction type is required")
        PostReactionType reaction
) {
}
