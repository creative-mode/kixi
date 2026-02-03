package ao.creativemode.kixi.dto.users;

import java.time.LocalDateTime;

public record UserResponse(
        Long id,
        Long accountId,
        String firstName,
        String lastName,
        String photo,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {}
