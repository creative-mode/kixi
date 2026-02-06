package ao.creativemode.kixi.dto.questionimage;

import java.time.LocalDateTime;

public record QuestionImageResponse(
        Long id,
        Long questionId,
        String imageUrl,
        String caption,
        Integer orderIndex,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {}