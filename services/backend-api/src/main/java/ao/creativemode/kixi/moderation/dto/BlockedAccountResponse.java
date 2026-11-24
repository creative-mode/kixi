package ao.creativemode.kixi.moderation.dto;

import java.time.LocalDateTime;

/** One entry in the caller's list of blocked people. */
public record BlockedAccountResponse(Long accountId, String displayName, String photo, LocalDateTime blockedAt) { }
