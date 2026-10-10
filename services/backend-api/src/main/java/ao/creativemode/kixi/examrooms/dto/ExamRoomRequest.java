package ao.creativemode.kixi.examrooms.dto;

import java.time.LocalDateTime;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ExamRoomRequest(
        @NotNull Long statementId,
        @NotNull Long classId,
        @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime endsAt,
        @NotNull @Positive Integer durationMinutes
) {
    /** Kept for callers compiled against the first draft; HTTP validation requires classId. */
    public ExamRoomRequest(Long statementId, LocalDateTime startsAt, LocalDateTime endsAt, Integer durationMinutes) {
        this(statementId, null, startsAt, endsAt, durationMinutes);
    }
}
