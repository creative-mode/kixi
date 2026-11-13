package ao.creativemode.kixi.examrooms.dto;

import java.time.LocalDateTime;
import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;
import ao.creativemode.kixi.examrooms.model.ExamRoomPhase;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record ExamRoomResponse(
        Long id, Long statementId, Long classId, Long teacherAccountId, LocalDateTime startsAt,
        LocalDateTime endsAt, Integer durationMinutes, ExamRoomStatus status, ExamRoomPhase phase,
        @JsonIgnore @io.swagger.v3.oas.annotations.media.Schema(hidden = true) boolean transitioned
) {}
