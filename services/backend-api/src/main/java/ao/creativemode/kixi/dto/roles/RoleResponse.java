package ao.creativemode.kixi.dto.roles;

import java.time.LocalDateTime;

public record RoleResponse(
        Long id,
        String name,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {}
