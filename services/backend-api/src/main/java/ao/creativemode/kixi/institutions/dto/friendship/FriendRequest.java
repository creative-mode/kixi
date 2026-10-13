package ao.creativemode.kixi.institutions.dto.friendship;

/**
 * Who to follow.
 *
 * @param accountId the colleague; the only thing a caller supplies, never a name, so a
 *                  request cannot be aimed at somebody by guessing a display name
 */
public record FriendRequest(
        Long accountId
) { }