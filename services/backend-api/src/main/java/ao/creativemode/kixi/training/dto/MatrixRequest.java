package ao.creativemode.kixi.training.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** A matriz de especificação de uma prova: temas, n.º de itens, cotação (numa escala, 20 por omissão) e nível cognitivo. */
public record MatrixRequest(
        String title,
        /** A escala das cotações; 20 por omissão. */
        Double totalPoints,
        @NotEmpty(message = "A matriz precisa de pelo menos um tema") List<@Valid MatrixItemRequest> items) {
}
