package ao.creativemode.kixi.training.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record QuestionTopicsRequest(@NotNull(message = "topicIds é obrigatório") List<Long> topicIds) {
}
