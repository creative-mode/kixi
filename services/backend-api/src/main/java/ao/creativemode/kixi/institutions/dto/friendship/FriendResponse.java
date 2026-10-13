package ao.creativemode.kixi.institutions.dto.friendship;

/**
 * One friendship, as the account holding one end of it sees it.
 *
 * <p>The name is here because both ends already agreed to know each other — that is what
 * the friendship is. The ranking masks names on its own and does not read this.</p>
 *
 * @param friendshipId how to accept or remove it
 * @param accountId    the other account; the caller's own id is never returned
 * @param displayName  first name plus initial, the same shape the ranking uses
 * @param status       {@code PENDING} or {@code ACCEPTED}
 * @param accepted     whether the ranking counts this person yet
 * @param incoming     true when this is a request addressed to the caller, so an inbox
 *                     and a list do not have to be told apart by the client
 */
public record FriendResponse(
        Long friendshipId,
        Long accountId,
        String displayName,
        String status,
        boolean accepted,
        boolean incoming
) { }