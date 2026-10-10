package ao.creativemode.kixi.examrooms.dto;

import ao.creativemode.kixi.exams.dto.questionoption.QuestionOptionResponse;
import java.time.LocalDateTime;
import java.util.List;

/** Student-facing question: alternatives are available, but never the answer key. */
public record ExamRoomQuestionResponse(
        Long id,
        Long statementId,
        Integer number,
        String text,
        String questionType,
        Double maxScore,
        Integer orderIndex,
        Integer pageIndex,
        List<QuestionOptionResponse> options,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) { }
