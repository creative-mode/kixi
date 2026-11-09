package ao.creativemode.kixi.training.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record MatrixPracticeRequest(
        @NotNull(message = "roomId é obrigatório") Long roomId,
        @NotEmpty(message = "Escolha pelo menos um tema") List<@Valid Item> items,
        @Min(value = 1, message = "A duração tem de ser positiva") @Max(value = 600, message = "A duração não pode passar de 600 minutos") Integer durationMinutes) {

    public record Item(
            @NotNull(message = "topicId é obrigatório") Long topicId,
            @NotNull(message = "count é obrigatório") @Min(value = 1, message = "O número de questões tem de ser pelo menos 1") @Max(value = 100, message = "No máximo 100 questões por tema") Integer count) {
    }
}
