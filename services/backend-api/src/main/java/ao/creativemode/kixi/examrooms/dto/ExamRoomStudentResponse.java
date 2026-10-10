package ao.creativemode.kixi.examrooms.dto;

import ao.creativemode.kixi.examrooms.model.ExamRoomStatus;
import ao.creativemode.kixi.exams.dto.question.QuestionResponse;
import java.time.LocalDateTime;
import java.util.List;

/** The student-facing room contract. It deliberately has no statement answer key. */
public record ExamRoomStudentResponse(
        Long roomId, Long simulationId, ExamRoomStatus status,
        LocalDateTime startsAt, LocalDateTime endsAt, Integer durationMinutes,
        List<QuestionResponse> questions) {}
