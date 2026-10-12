package ao.creativemode.kixi.feed.dto.post;

import ao.creativemode.kixi.feed.enums.PostType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code classId} is the only scope the client names; the school, the course
 * and the school year are derived from it on the server (issue #120, point 4
 * of the review: a client-supplied scope cannot be trusted).
 */
public record PostRequest(
        @NotNull(message = "Post type is required")
        PostType type,

        @NotNull(message = "classId is required")
        Long classId,

        String title,

        @NotBlank(message = "Content cannot be empty")
        @Size(min = 5, max = 2000)
        String content
) {}
