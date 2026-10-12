package ao.creativemode.kixi.institutions.service;

import java.util.Locale;

import ao.creativemode.kixi.institutions.dto.friendship.ColleagueResponse;
import ao.creativemode.kixi.institutions.dto.friendship.FriendRequest;
import ao.creativemode.kixi.institutions.dto.friendship.FriendResponse;
import ao.creativemode.kixi.institutions.model.Friendship;
import ao.creativemode.kixi.institutions.repository.FriendshipRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.identity.repository.UserRepository;
import ao.creativemode.kixi.shared.service.EnrollmentSeatReader;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Following a colleague, and the consent that turns a request into a friendship
 * (issue #121).
 *
 * <p>The consent is the design. This is what {@code scope=friends} ranks over, so unlike
 * a class ranking — where a student sits beside people they were simply placed with —
 * every row here was agreed to by both accounts. That is why the small-group floor does
 * not apply to the friends ranking; see {@code LeaderboardService}.</p>
 */
@Service
public class FriendshipService {

    /** Enough to recognise somebody, few enough that the answer stays a page. */
    static final int SEARCH_LIMIT = 20;

    private final FriendshipRepository friendships;
    private final EnrollmentSeatReader seats;
    private final UserRepository users;

    public FriendshipService(
            FriendshipRepository friendships, EnrollmentSeatReader seats, UserRepository users) {
        this.friendships = friendships;
        this.seats = seats;
        this.users = users;
    }

    /**
     * Ask to be someone's friend.
     *
     * <p>Asking again after a removal restores the request rather than failing: the pair
     * keeps one row either way, because the canonical ordering makes it unique.</p>
     */
    public Mono<FriendResponse> request(Long fromAccountId, FriendRequest data) {
        Long other = data.accountId();
        if (other == null) {
            return Mono.error(ApiException.badRequest("An account is required to follow someone"));
        }
        if (other.equals(fromAccountId)) {
            return Mono.error(ApiException.badRequest("You cannot follow yourself"));
        }

        Long low = Math.min(fromAccountId, other);
        Long high = Math.max(fromAccountId, other);

        return inSameSchoolYear(fromAccountId, other)
                .then(friendships.findBetween(low, high)
                        .flatMap(existing -> Mono.just(revive(existing, fromAccountId)))
                        .switchIfEmpty(Mono.fromSupplier(() -> Friendship.between(
                                fromAccountId, other, fromAccountId)))
                        .flatMap(friendships::save)
                        .flatMap(friendship -> decorate(friendship, fromAccountId)));
    }

    /**
     * Accept a request addressed to the caller.
     *
     * <p>Answering for somebody else is a 404 and not a 403: from the caller's side there
     * is no such request, and a 403 would confirm that somebody asked them.</p>
     */
    public Mono<FriendResponse> accept(Long accountId, Long friendshipId) {
        return friendships.findByIdAndDeletedAtIsNull(friendshipId)
                .filter(friendship -> friendship.getAccountHigh().equals(accountId))
                .switchIfEmpty(Mono.error(ApiException.notFound(
                        "Friend request not found: " + friendshipId)))
                .flatMap(friendship -> {
                    friendship.accept();
                    return Mono.just(friendship);
                })
                .flatMap(friendships::save)
                .flatMap(friendship -> decorate(friendship, accountId));
    }

    /** Remove the friendship, or withdraw a request. Either side may do it. */
    public Mono<Void> remove(Long accountId, Long friendshipId) {
        return friendships.findByIdAndDeletedAtIsNull(friendshipId)
                .filter(friendship -> friendship.involves(accountId))
                .switchIfEmpty(Mono.error(ApiException.notFound(
                        "Friendship not found: " + friendshipId)))
                .flatMap(friendship -> {
                    friendship.softDelete();
                    return Mono.just(friendship);
                })
                .flatMap(friendships::save)
                .then();
    }

    /** The people whose marks this account may see through {@code scope=friends}. */
    public Flux<FriendResponse> listFriends(Long accountId) {
        return decorateAll(friendships.findAcceptedBy(accountId), accountId);
    }

    /** The inbox: requests from other people waiting for an answer. */
    public Flux<FriendResponse> listRequests(Long accountId) {
        return decorateAll(friendships.findPendingFor(accountId), accountId);
    }

    /**
     * Colleagues of the caller's own school year who are not already in the list.
     *
     * <p>Discovery is the only way two people who do not know each other ever become
     * friends here, so it is deliberately narrow: one school, one school year, nobody
     * already requested.</p>
     */
    public Flux<ColleagueResponse> searchColleagues(Long accountId, String term) {
        return currentSeatOf(accountId)
                .switchIfEmpty(Mono.error(ApiException.forbidden(
                        "An active enrollment in a school is needed to search colleagues")))
                .flatMapMany(seat -> friendships.searchColleagues(
                        accountId, seat.schoolYearId(), seat.institutionId(),
                        term == null || term.isBlank() ? null : term.trim(),
                        SEARCH_LIMIT))
                .map(user -> new ColleagueResponse(
                        user.getAccountId(), mask(user.getFirstName(), user.getLastName())));
    }

    /**
     * Both accounts in the same school year.
     *
     * <p>Friendships stay inside a school: the ranking reads marks, and marks are a
     * school's business. A cross-school friendship would need the privacy decision that
     * is still open in the comparison issue, and this does not quietly pre-empt it.</p>
     */
    private Mono<Void> inSameSchoolYear(Long fromAccountId, Long toAccountId) {
        return currentSeatOf(fromAccountId)
                .switchIfEmpty(Mono.error(ApiException.forbidden(
                        "An active enrollment is needed to follow a colleague")))
                .flatMap(mine -> currentSeatOf(toAccountId)
                        // Both halves. school_year_id is a global row shared by every
                        // school, so comparing years alone let a student at one school
                        // follow a student at another — and the ranking then showed one
                        // school's marks to the other.
                        .filter(theirs -> mine.institutionId() != null
                                && mine.institutionId().equals(theirs.institutionId())
                                && mine.schoolYearId().equals(theirs.schoolYearId()))
                        .switchIfEmpty(Mono.error(ApiException.badRequest(
                                "That colleague is not in your school this year"))))
                .then();
    }

    /**
     * The caller's current school year.
     *
     * <p>Through {@link EnrollmentSeatReader} rather than the enrollments directly, so
     * "which year is this student in" is answered by the same rule the ranking uses. Two
     * places deciding that differently would rank friends over a different year than the
     * one they were added in.</p>
     */
    private Mono<EnrollmentSeatReader.Seat> currentSeatOf(Long accountId) {
        // The whole seat, not just the year: the school is the half that isolates.
        return seats.activeSeatsOf(accountId)
                .filter(seat -> seat.institutionId() != null)
                .next();
    }

    private Flux<FriendResponse> decorateAll(Flux<Friendship> source, Long viewerAccountId) {
        return source
                .flatMap(friendship -> decorate(friendship, viewerAccountId), 8);
    }

    private Mono<FriendResponse> decorate(Friendship friendship, Long viewerAccountId) {
        Long other = friendship.otherThan(viewerAccountId);
        boolean accepted = Friendship.ACCEPTED.equals(friendship.getStatus());
        boolean incoming = Friendship.PENDING.equals(friendship.getStatus())
                && !friendship.getRequestedBy().equals(viewerAccountId);

        return displayNameOf(other)
                .map(displayName -> new FriendResponse(
                        friendship.getId(),
                        other,
                        displayName,
                        friendship.getStatus(),
                        accepted,
                        incoming));
    }

    private static Friendship revive(Friendship existing, Long requester) {
        existing.restore();
        existing.setRequestedBy(requester);
        existing.setStatus(Friendship.PENDING);
        return existing;
    }

    /**
     * First name and the initial of the last one — the same shape the ranking uses.
     *
     * <p>Same format on purpose. A friend list that showed "Ana Silva" next to a ranking
     * that showed "Ana S." would be handing out the surname to people who agreed to see
     * each other, which is not a thing either of them asked for.</p>
     */
    private Mono<String> displayNameOf(Long accountId) {
        return users.findByAccountIdAndDeletedAtIsNull(accountId)
                .next()
                .map(user -> mask(user.getFirstName(), user.getLastName()))
                .defaultIfEmpty("Aluno");
    }

    static String mask(String firstName, String lastName) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        String initial = last.isEmpty() ? "" : last.substring(0, 1).toUpperCase(Locale.ROOT) + ".";
        String masked = (first + " " + initial).trim();
        return masked.isEmpty() ? "Aluno" : masked;
    }
}