package ao.creativemode.kixi.exams.dto.questionimage;

import java.time.LocalDateTime;

public record QuestionImageResponse(
        Long id,
        Long questionId,
        String imageUrl,
        String caption,
        Integer orderIndex,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt,
        String ocrKind,
        Integer ocrPageIndex,
        Double ocrScore
) {
    /** Preserve the response constructor used before OCR region provenance was exposed. */
    public QuestionImageResponse(Long id, Long questionId, String imageUrl, String caption, Integer orderIndex,
            LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime deletedAt) {
        this(id, questionId, imageUrl, caption, orderIndex, createdAt, updatedAt, deletedAt, null, null, null);
    }
}
