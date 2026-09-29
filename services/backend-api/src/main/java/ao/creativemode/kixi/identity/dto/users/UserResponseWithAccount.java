package ao.creativemode.kixi.identity.dto.users;

import ao.creativemode.kixi.identity.dto.accounts.AccountBasicResponse;
import java.time.LocalDateTime;

public record UserResponseWithAccount(
        Long id,
        AccountBasicResponse account,
        String firstName,
        String lastName,
        String photo,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {}
