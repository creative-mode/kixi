package ao.creativemode.kixi.training.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Um tema da matriz de uma prova. Indica-se o tema por {@code topicId} ou, para criar o tema na disciplina do
 * enunciado da sala se ainda não existir, por {@code topicName}.
 */
public record MatrixItemRequest(
        Long topicId,
        String topicName,
        @NotNull(message = "O número de questões é obrigatório") @Min(value = 1, message = "O número de questões tem de ser pelo menos 1") Integer questionCount,
        Double points,
        /** KNOWLEDGE, COMPREHENSION, APPLICATION ou ANALYSIS (aceita-se também em português). */
        String cognitiveLevel,
        List<String> questionTypes) {
}
