package ao.creativemode.kixi.dto.term;

import java.time.LocalDateTime;

public record TermResponse(
        Long id,
        int number,
        String name,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {}