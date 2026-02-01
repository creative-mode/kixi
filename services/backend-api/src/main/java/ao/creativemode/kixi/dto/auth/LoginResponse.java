package ao.creativemode.kixi.dto.auth;

import java.time.Instant;
import java.util.List;

public record LoginResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        Long accountId,
        List<String> roles
) {
    public static final String TOKEN_TYPE = "Bearer";
}
