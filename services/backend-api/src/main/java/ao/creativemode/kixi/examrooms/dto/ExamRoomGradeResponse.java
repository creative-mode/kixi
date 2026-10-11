package ao.creativemode.kixi.examrooms.dto;

import java.time.LocalDateTime;

public record ExamRoomGradeResponse(
        Long accountId,
        Long simulationId,
        String status,
        Double finalScore,
        Integer correctAnswers,
        Integer totalQuestions,
        Integer pendingReviewCount,
        LocalDateTime finishedAt
) { }
