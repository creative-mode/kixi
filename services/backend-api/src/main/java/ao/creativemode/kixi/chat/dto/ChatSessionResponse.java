package ao.creativemode.kixi.chat.dto;

import ao.creativemode.kixi.chat.model.TutorMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A tutor session. Exactly one context is set: {@code statementId} for the
 * tutor of a paper (#114/#115), or {@code subjectId}/{@code topicId} for the
 * tutor outside one (#610). {@code sources} are the statement and, when there
 * is one, the question in focus (issue #115); for a subject/topic session they
 * are the papers its catalog material came from.
 */
public record ChatSessionResponse(
    Long id,
    Long statementId,
    Long subjectId,
    Long topicId,
    LocalDateTime createdAt,
    TutorMode mode,
    Long questionId,
    List<TutorSource> sources
) {
    /** A session of #114: no mode, the statement as its only source. */
    public ChatSessionResponse(Long id, Long statementId, LocalDateTime createdAt) {
        this(id, statementId, null, null, createdAt, TutorMode.GENERAL, null, List.of());
    }
}
