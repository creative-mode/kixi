package ao.creativemode.kixi.gamification.dto;

import java.time.LocalDate;

public record UserStatsResponse(
        Long accountId,
        Long xp,
        Integer level,
        Integer streak,
        Integer longestStreak,
        Integer freePauses,
        LocalDate lastActivityDate
) { }
