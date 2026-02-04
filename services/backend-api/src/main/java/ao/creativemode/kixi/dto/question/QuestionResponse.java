package ao.creativemode.kixi.dto.question;

import java.time.LocalDateTime;

public record QuestionResponse(
        Long id,
        Long statementId,
        Integer number,
        String text,
        String questionType,
        Double maxScore,
        Integer orderIndex,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {}