package ao.creativemode.kixi.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Payload of every server-sent event of a tutor answer, discriminated by
 * {@code type} (the SSE {@code event} name carries it too):
 *
 * <ul>
 *   <li>{@code delta} — a fragment of the answer text, in order;</li>
 *   <li>{@code final} — the stream finished; {@code messageId} identifies the
 *       stored assistant message;</li>
 *   <li>{@code error} — the answer failed mid-stream; the HTTP status was 200
 *       by then, so the failure is reported in-band with a stable code.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatStreamEvent(
    String type,
    String content,
    Long messageId,
    String model,
    String code,
    String message
) {

    public static ChatStreamEvent delta(String content) {
        return new ChatStreamEvent("delta", content, null, null, null, null);
    }

    public static ChatStreamEvent finished(Long messageId, String model) {
        // No tokensUsed here: Groq does not report usage on streaming
        // completions, so the column stays null until it does.
        return new ChatStreamEvent("final", null, messageId, model, null, null);
    }

    public static ChatStreamEvent error(String code, String message) {
        return new ChatStreamEvent("error", null, null, null, code, message);
    }
}
