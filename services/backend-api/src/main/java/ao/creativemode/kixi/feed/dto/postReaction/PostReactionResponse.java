package ao.creativemode.kixi.feed.dto.postReaction;

import java.time.LocalDateTime;

public record PostReactionResponse(
        Long id,
        Long postId,
        Long accountId,
        String type,
        LocalDateTime createdAt
) {}