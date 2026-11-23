package ao.creativemode.kixi.chat.dto;

import ao.creativemode.kixi.chat.model.TutorMode;
import jakarta.validation.constraints.Size;

/**
 * Opens a tutor session bound to one context the caller is allowed to read.
 *
 * <p>A session has exactly one context. The tutor of issues #114/#115 is bound to
 * a {@code statementId}; issue #610 adds the tutor that exists outside one paper:
 * {@code subjectId} for a whole subject, or {@code topicId} for one topic of the
 * exam matrix (kixi#581). A topic carries its subject, so {@code topicId} alone
 * is enough; when both are sent the topic must belong to that subject.</p>
 *
 * <p>The statement context keeps everything of #115. The subject/topic context is
 * only for {@link TutorMode#GENERAL} and {@link TutorMode#CONCEPT}: the modes
 * that walk through one question still need a paper to point at.</p>
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
    Long statementId,
    Long subjectId,
    Long topicId,
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
        return new ChatSessionRequest(statementId, null, null, null, null, null, null);
    }

    /** The request of #610: a whole subject, or one topic of its exam matrix. */
    public static ChatSessionRequest ofSubject(Long subjectId, Long topicId) {
        return new ChatSessionRequest(null, subjectId, topicId, null, null, null, null);
    }

    public boolean hasStatementContext() {
        return statementId != null;
    }

    public boolean hasSubjectContext() {
        return subjectId != null || topicId != null;
    }
}
