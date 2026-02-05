package ao.creativemode.kixi.dto.classe;

import jakarta.validation.constraints.NotNull;

public record ClassRequest(
    @NotNull(message = "Code cannot be null") String code,
    @NotNull(message = "Grade is required") Integer grade,
    @NotNull(message = "course id cannot be null") Long courseId,
    @NotNull(message = "school year id cannot be null") Long schoolYearId
) {}
