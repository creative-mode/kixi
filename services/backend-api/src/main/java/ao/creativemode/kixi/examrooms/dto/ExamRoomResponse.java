package ao.creativemode.kixi.examrooms.dto;

import java.time.LocalDateTime;
import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;

public record ExamRoomResponse(
        Long id, Long statementId, Long teacherAccountId, LocalDateTime startsAt,
        LocalDateTime endsAt, Integer durationMinutes, ExamRoomStatus status
) {}
