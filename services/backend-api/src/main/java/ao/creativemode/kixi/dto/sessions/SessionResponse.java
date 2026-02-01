package ao.creativemode.kixi.dto.sessions;

import java.time.LocalDateTime;

public record SessionResponse(
        Long id,
        Long accountId,
        String token,
        String ipAddress,
        LocalDateTime expiresAt,
        LocalDateTime lastUsed,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
){}