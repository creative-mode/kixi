package ao.creativemode.kixi.dto.accounts;

import java.time.LocalDateTime;

public record AccountResponse(
    Long id,
    String username,
    String email,
    Boolean emailVerified,
    Boolean active,
    LocalDateTime lastLogin,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime deletedAt
) {}
