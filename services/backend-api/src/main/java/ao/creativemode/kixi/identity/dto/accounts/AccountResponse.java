package ao.creativemode.kixi.identity.dto.accounts;

import java.time.LocalDateTime;

public record AccountResponse(
    Long id,
    String username,
    String email,
    Boolean emailVerified,
    Boolean active,
    Boolean accessibilityExtraTime,
    LocalDateTime lastLogin,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime deletedAt
) {}
