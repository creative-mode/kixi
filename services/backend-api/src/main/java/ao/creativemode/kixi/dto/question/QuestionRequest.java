package ao.creativemode.kixi.dto.question;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record QuestionRequest(
        @NotNull(message = "Statement ID is required")
        Long statementId,

        @NotNull(message = "Question number is required")
        @Min(value = 1, message = "Number must be at least 1")
        Integer number,

        @NotBlank(message = "Question text is required")
        @Size(max = 2000, message = "Text cannot exceed 2000 characters")
        String text,

        @NotBlank(message = "Question type is required")
        String questionType,

        @NotNull(message = "Max score is required")
        @Min(value = 0, message = "Max score cannot be negative")
        Double maxScore,

        Integer orderIndex
) {}