package ao.creativemode.kixi.dto.classe;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.NumberFormat;

public record ClassRequest(
        @NotBlank(message = "Code is required")
        @NotNull(message = "Code cannot be null")
        String code,
        @NotBlank(message = "Gradeis required")
        @NotNull(message = "Grade cannot be null")
        String grade,
        @NotNull(message="course id cannot be null")
        Long courseId,
        @NotNull(message="school year id cannot be null")
        Long schoolYearId
) {
}
