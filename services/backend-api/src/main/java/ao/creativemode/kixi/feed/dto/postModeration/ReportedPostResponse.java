package ao.creativemode.kixi.feed.dto.postModeration;

import java.time.LocalDateTime;

/** One post that has at least one report pending moderation, for the queue a teacher or ADMIN works from. */
public record ReportedPostResponse(
        Long postId,
        Long authorAccountId,
        Long classId,
        String content,
        boolean isHidden,
        long reportCount,
        LocalDateTime createdAt
) {}
