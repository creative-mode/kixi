package ao.creativemode.kixi.feed.dto.postComment;

import java.time.LocalDateTime;

/**
 * One comment on a post.
 *
 * @param displayName the author's name, masked as first name plus the initial of the
 *                    last — the same shape the leaderboard uses. Null when the account
 *                    has no profile, which is not a person and does not get a label.
 * @param authorPhoto the author's stored photo address (a {@code /uploads/...} path), null when there is none;
 *                    it comes with the comment so the client does not read one profile per commenter
 * @param mine        true when the caller wrote it, so the client can mark its own rows
 *                    without comparing account ids itself
 */
public record PostCommentResponse(
        Long id,
        Long postId,
        Long authorAccountId,
        String content,
        Boolean isHidden,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Boolean mine,
        String displayName,
        String authorPhoto
) {}
