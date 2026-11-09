package ao.creativemode.kixi.training.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Um tema de uma disciplina, opcionalmente só de uma classe; as palavras-chave, separadas por vírgula, ajudam a etiquetar. */
public record TopicRequest(
        @NotBlank(message = "O nome do tema é obrigatório") @Size(max = 200, message = "O nome do tema não pode passar de 200 caracteres") String name,
        @Min(value = 1, message = "A classe tem de ser positiva") Integer grade,
        String keywords) {
}
