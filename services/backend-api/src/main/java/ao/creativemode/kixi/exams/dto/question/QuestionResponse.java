package ao.creativemode.kixi.exams.dto.question;

import java.time.LocalDateTime;

public record QuestionResponse(
        Long id,
        Long statementId,
        Integer number,
        String text,
        String questionType,
        Double maxScore,
        Integer orderIndex,
        Integer pageIndex,
        String modelAnswer,
        Boolean needsReview,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) { }