package ao.creativemode.kixi.gamification.dto;

import java.time.LocalDateTime;

public record UserBadgeResponse(
        String code,
        String name,
        String description,
        Long subjectId,
        LocalDateTime unlockedAt
) { }
