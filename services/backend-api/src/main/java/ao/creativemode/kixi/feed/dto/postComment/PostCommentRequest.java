package ao.creativemode.kixi.feed.dto.postComment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PostCommentRequest(
        @NotBlank(message = "Comment content cannot be empty")
        @Size(max = 500, message = "Comment cannot exceed 500 characters")
        String content
) {}