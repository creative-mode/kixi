package ao.creativemode.kixi.dto.accounts;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountRequest(
    @NotBlank(message = "Username é obrigatório")
    @Size(min = 3, max = 100, message = "Username deve ter entre 3 e 100 caracteres")
    String username,

    @NotBlank(message = "Email é obrigatório")
    @Email(message = "Email deve ser válido")
    @Size(max = 255, message = "Email não pode exceder 255 caracteres")
    String email,

    @NotBlank(message = "Palavra-passe é obrigatória")
    @Size(min = 8, max = 255, message = "Palavra-passe deve ter entre 8 e 255 caracteres")
    String password
) {}
