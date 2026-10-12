package ao.creativemode.kixi.chat.dto;

import ao.creativemode.kixi.chat.model.TutorMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Opens a tutor session bound to a statement the caller is allowed to read.
 * The statement is what scopes the tutor — without it there is nothing the
 * tutor may answer from (issue #114: restricted to official statements).
 *
 * <p>Issue #115 adds what the session is for. All of it is optional, so the
 * request of #114 ({@code statementId} alone) still opens the same tutor:</p>
 * <ul>
 *   <li>{@code mode}: {@code EXPLAIN_QUESTION}, {@code SIMILAR_EXERCISES} or
 *       {@code CONCEPT}; {@code GENERAL} when absent. The first two need a
 *       {@code questionId};</li>
 *   <li>{@code questionId}: a question of that statement;</li>
 *   <li>{@code selectedOptionId} or {@code studentAnswer}: the (wrong) answer the
 *       student gave to that question, for "explicar questão".</li>
 * </ul>
 */
public record ChatSessionRequest(
    @NotNull(message = "statementId is required")
    Long statementId,
    TutorMode mode,
    Long questionId,
    Long selectedOptionId,
    @Size(max = 2000, message = "studentAnswer must be at most 2000 characters")
    String studentAnswer
) {
    /**
     * The request of #114. A factory rather than a second constructor, so Jackson
     * has only the canonical one to bind the JSON body to.
     */
    public static ChatSessionRequest of(Long statementId) {
        return new ChatSessionRequest(statementId, null, null, null, null);
    }
}
