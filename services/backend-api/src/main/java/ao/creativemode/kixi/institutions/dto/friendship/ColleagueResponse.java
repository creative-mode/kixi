package ao.creativemode.kixi.institutions.dto.friendship;

/**
 * A colleague the caller may ask, found in their own school year.
 *
 * <p>Deliberately thin: an id and a name to recognise. No marks, no ranking, no school —
 * a search result is a starting point for a request, not a relationship yet.</p>
 */
public record ColleagueResponse(
        Long accountId,
        String displayName
) { }