package ao.creativemode.kixi.feed.dto.post;

import ao.creativemode.kixi.feed.enums.PostType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

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

        /** Required for a DOUBT or TOPIC; a SIMULATION card may go without a note. */
        String content,

        /** Only for a SIMULATION post: the finished attempt to publish. The figures come from the server. */
        Long simulationId
) {

    private static final int MAX_CONTENT = 2000;
    private static final int MIN_TEXT_CONTENT = 5;

    /**
     * A text post needs 5 to 2000 characters; a SIMULATION card's note is optional but
     * still capped. Checked here, on the DTO, so a bad body is a 400 before the service.
     */
    @JsonIgnore
    @AssertTrue(message = "Content must have 5 to 2000 characters (optional for SIMULATION posts)")
    public boolean isContentValid() {
        if (type == null) {
            return true; // reported by @NotNull on type
        }
        int length = content == null ? 0 : content.trim().length();
        if (length > MAX_CONTENT) {
            return false;
        }
        return type == PostType.SIMULATION || length >= MIN_TEXT_CONTENT;
    }
}
