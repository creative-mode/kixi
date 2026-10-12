package ao.creativemode.kixi.chat.dto;

import ao.creativemode.kixi.chat.model.TutorMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A tutor session. {@code sources} are the statement and, when there is one, the
 * question in focus (issue #115); the catalog questions of "exercícios
 * semelhantes" are chosen per answer and come in the {@code final} event.
 */
public record ChatSessionResponse(
    Long id,
    Long statementId,
    LocalDateTime createdAt,
    TutorMode mode,
    Long questionId,
    List<TutorSource> sources
) {
    /** A session of #114: no mode, the statement as its only source. */
    public ChatSessionResponse(Long id, Long statementId, LocalDateTime createdAt) {
        this(id, statementId, createdAt, TutorMode.GENERAL, null, List.of());
    }
}
