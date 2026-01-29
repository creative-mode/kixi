package ao.creativemode.kixi.dto.users;

import ao.creativemode.kixi.dto.accounts.AccountBasicResponse;
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
