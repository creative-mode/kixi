package ao.creativemode.kixi.dto.questionimage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record QuestionImageRequest(
        @NotNull(message = "Question ID is required")
        Long questionId,

        @NotBlank(message = "Image URL is required")
        String imageUrl,

        String caption,

        Integer orderIndex
) {}