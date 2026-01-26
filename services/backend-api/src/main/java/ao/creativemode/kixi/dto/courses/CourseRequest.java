package ao.creativemode.kixi.dto.courses;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CourseRequest(
    @NotBlank(message = "Código é obrigatório")
    @Size(min = 2, max = 50, message = "Código deve ter entre 2 e 50 caracteres")
    String code,

    @NotBlank(message = "Nome é obrigatório")
    @Size(min = 3, max = 255, message = "Nome deve ter entre 3 e 255 caracteres")
    String name,

    @Size(max = 5000, message = "Descrição não pode exceder 5000 caracteres")
    String description
) {}
