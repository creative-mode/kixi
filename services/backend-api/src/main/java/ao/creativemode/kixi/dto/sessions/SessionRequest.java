package ao.creativemode.kixi.dto.sessions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SessionRequest(
        @NotNull(message = "Account ID is required")
        Long accountId,

        @NotBlank(message = "Token is required")
        String token,

        @NotBlank(message = "IP Address is required")
        String ipAddress,

        java.time.LocalDateTime expiresAt
) {}