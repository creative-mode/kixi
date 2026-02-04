package ao.creativemode.kixi.dto.classe;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;


public record ClassRequest(
        @NotBlank(message = "code required")
        @NotNull(message = "Code cannot be null")
        String code,
        @NotBlank(message = "Grade is required")
        @NotNull(message = "Grade cannot be null")
        String grade,
        @NotNull(message="course id cannot be null")
        Long courseId,
        @NotNull(message="school year id cannot be null")
        Long schoolYearId
) {
}
