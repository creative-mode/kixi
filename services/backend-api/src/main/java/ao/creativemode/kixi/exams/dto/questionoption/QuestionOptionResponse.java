package ao.creativemode.kixi.exams.dto.questionoption;

import java.time.LocalDateTime;

public record QuestionOptionResponse(
        Long id,
        Long questionId,
        String optionLabel,
        String optionText,
        Boolean isCorrect,
        Integer orderIndex,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) { }