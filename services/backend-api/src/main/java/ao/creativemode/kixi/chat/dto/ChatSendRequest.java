package ao.creativemode.kixi.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** One user turn of a session; answered over SSE (issue #114). */
public record ChatSendRequest(
    @NotBlank(message = "content is required")
    @Size(max = 4000, message = "content must be at most 4000 characters")
    String content
) {}
