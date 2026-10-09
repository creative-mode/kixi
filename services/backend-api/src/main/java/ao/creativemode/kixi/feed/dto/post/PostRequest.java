package ao.creativemode.kixi.feed.dto.post;

import ao.creativemode.kixi.feed.enums.PostType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PostRequest(
        @NotNull(message = "Post type is required")
        PostType type,

        String title,

        @NotBlank(message = "Content cannot be empty")
        @Size(min = 5, max = 2000)
        String content
) {}
