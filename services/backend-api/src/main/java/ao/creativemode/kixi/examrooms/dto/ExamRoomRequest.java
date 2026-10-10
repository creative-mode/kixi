package ao.creativemode.kixi.examrooms.dto;

import java.time.LocalDateTime;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ExamRoomRequest(
        @NotNull Long statementId,
        @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime endsAt,
        @NotNull @Positive Integer durationMinutes
) {}
