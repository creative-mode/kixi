package ao.creativemode.kixi.feed.dto.postComment;

import java.time.LocalDateTime;

public record PostCommentResponse(
        Long id,
        Long postId,
        Long authorAccountId,
        String content,
        Boolean isHidden,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}