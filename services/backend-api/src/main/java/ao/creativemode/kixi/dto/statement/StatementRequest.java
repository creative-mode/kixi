package ao.creativemode.kixi.dto.statement;

import jakarta.validation.constraints.*;

public record StatementRequest(
        @NotBlank(message = "O título é obrigatório")
        @Size(min = 3, max = 255, message = "O título deve ter entre 3 e 255 caracteres")
        String title,

        @NotBlank(message = "O tipo de exame é obrigatório")
        String examType,

        @Positive(message = "A duração deve ser maior que zero")
        Integer durationMinutes,

        @Size(max = 50, message = "A variante deve ter no máximo 50 caracteres")
        String variant,

        @Size(max = 5000, message = "As instruções devem ter no máximo 5000 caracteres")
        String instructions,

        @PositiveOrZero(message = "A pontuação máxima não pode ser negativa")
        Integer totalMaxScore,

        @NotNull(message = "O ano letivo é obrigatório")
        Long schoolYearId,

        @NotNull(message = "O trimestre é obrigatório")
        Long termId,

        @NotNull(message = "A disciplina é obrigatória")
        Long subjectId,

        @NotNull(message = "A turma é obrigatória")
        Long classId,

        Long courseId,

        Boolean visible
) {}
