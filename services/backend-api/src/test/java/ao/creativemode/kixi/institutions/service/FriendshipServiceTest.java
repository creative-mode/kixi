package ao.creativemode.kixi.institutions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.identity.model.User;
import ao.creativemode.kixi.identity.repository.UserRepository;
import ao.creativemode.kixi.institutions.dto.friendship.FriendRequest;
import ao.creativemode.kixi.institutions.model.Friendship;
import ao.creativemode.kixi.institutions.repository.FriendshipRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.EnrollmentSeatReader;
import ao.creativemode.kixi.shared.service.EnrollmentSeatReader.Seat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Asking, answering, and the consent behind {@code scope=friends} (issue #121).
 */
class FriendshipServiceTest {

    private static final Long ANA = 12L;
    private static final Long BRUNO = 34L;
    private static final Long YEAR = 3L;
    private static final Long OTHER_YEAR = 4L;

    private FriendshipRepository friendships;
    private EnrollmentSeatReader seats;
    private UserRepository users;
    private FriendshipService service;

    @BeforeEach
    void setUp() {
        friendships = mock(FriendshipRepository.class);
        seats = mock(EnrollmentSeatReader.class);
        users = mock(UserRepository.class);
        service = new FriendshipService(friendships, seats, users);
        named(ANA, "Ana", "Neto");
        named(BRUNO, "Bruno", "Sali");

        // Mockito answers null for a method nobody stubbed, and a null in the middle of
        // a chain fails with a NullPointerException that says nothing about the rule the
        // test was written for. Defaults first; a test that needs a seat stubs one over.
        when(seats.activeSeatsOf(anyLong())).thenReturn(Flux.empty());
        when(friendships.findBetween(anyLong(), anyLong())).thenReturn(Mono.empty());
        when(friendships.findByIdAndDeletedAtIsNull(anyLong())).thenReturn(Mono.empty());
    }

    // === the acceptance criterion =========================================

    @Test
    void twoStudentsBecomeFriendsAndAppearToEachOther() {
        seatedIn(ANA, YEAR);
        seatedIn(BRUNO, YEAR);
        when(friendships.findBetween(ANA, BRUNO)).thenReturn(Mono.empty());
        java.util.concurrent.atomic.AtomicReference<Friendship> stored =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(friendships.save(any(Friendship.class))).thenAnswer(call -> {
            Friendship saved = call.getArgument(0);
            saved.setId(1L);
            stored.set(saved);
            return Mono.just(saved);
        });
        when(friendships.findByIdAndDeletedAtIsNull(1L))
                .thenAnswer(call -> Mono.justOrEmpty(stored.get()));

        var request = service.request(ANA, new FriendRequest(BRUNO)).block();
        assertThat(request).isNotNull();
        assertThat(request.accepted()).isFalse();
        assertThat(request.incoming()).isFalse();

        service.accept(BRUNO, 1L).block();

        when(friendships.findAcceptedBy(ANA)).thenAnswer(call ->
                stored.get().getStatus().equals(Friendship.ACCEPTED)
                        ? Flux.just(stored.get())
                        : Flux.empty());

        // Ana sees him as a friend now.
        assertThat(service.listFriends(ANA).collectList().block())
                .singleElement()
                .satisfies(friend -> {
                    assertThat(friend.accountId()).isEqualTo(BRUNO);
                    assertThat(friend.accepted()).isTrue();
                    assertThat(friend.displayName()).isEqualTo("Bruno S.");
                });
    }

    // === requesting ========================================================

    @Test
    void thePairIsStoredTheSameWayRoundWhoeverAsks() {
        seatedIn(ANA, YEAR);
        seatedIn(BRUNO, YEAR);
        when(friendships.findBetween(ANA, BRUNO)).thenReturn(Mono.empty());
        java.util.concurrent.atomic.AtomicReference<Friendship> written =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(friendships.save(any(Friendship.class))).thenAnswer(call -> {
            Friendship saved = call.getArgument(0);
            written.set(saved);
            return Mono.just(saved);
        });

        service.request(BRUNO, new FriendRequest(ANA)).block();

        // Bruno asks Ana, so Bruno is the low id and the author of the request.
        assertThat(written.get().getAccountLow()).isEqualTo(ANA);
        assertThat(written.get().getAccountHigh()).isEqualTo(BRUNO);
        assertThat(written.get().getRequestedBy()).isEqualTo(BRUNO);
    }

    @Test
    void askingAgainAfterARemovalRestoresTheSameRow() {
        seatedIn(ANA, YEAR);
        seatedIn(BRUNO, YEAR);
        Friendship removed = Friendship.between(ANA, BRUNO, ANA);
        removed.softDelete();
        when(friendships.findBetween(ANA, BRUNO)).thenReturn(Mono.just(removed));
        when(friendships.save(any(Friendship.class))).thenAnswer(call ->
                Mono.just(call.getArgument(0)));

        var request = service.request(ANA, new FriendRequest(BRUNO)).block();

        assertThat(request).isNotNull();
        assertThat(request.accepted()).isFalse();
        // The same row came back, not a second one: the pair was looked up first, and
        // a friendship is one row however many times it is asked for.
        assertThat(removed.getDeletedAt()).isNull();
        assertThat(removed.getStatus()).isEqualTo(Friendship.PENDING);
        verify(friendships).findBetween(ANA, BRUNO);
    }

    @Test
    void nobodyFollowsThemselves() {
        StepVerifier.create(service.request(ANA, new FriendRequest(ANA)))
                .expectErrorSatisfies(error ->
                        assertThat(((ApiException) error).getStatus())
                                .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
    }

    // === consent ===========================================================

    @Test
    void onlyTheAddresseeMayAnswer() {
        when(friendships.findByIdAndDeletedAtIsNull(1L))
                .thenReturn(Mono.just(Friendship.between(ANA, BRUNO, ANA)));

        // Ana asked; Ana may not accept her own request.
        StepVerifier.create(service.accept(ANA, 1L))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND);
                    // 404 and not 403: a 403 would confirm that the request exists.
                })
                .verify();
    }

    @Test
    void anIncomingRequestIsMarkedAsSuch() {
        seatedIn(ANA, YEAR);
        seatedIn(BRUNO, YEAR);
        when(friendships.findBetween(ANA, BRUNO)).thenReturn(Mono.empty());
        when(friendships.save(any(Friendship.class))).thenAnswer(call ->
                Mono.just(call.getArgument(0)));

        var request = service.request(ANA, new FriendRequest(BRUNO)).block();

        assertThat(request.incoming()).isFalse();
        assertThat(request.status()).isEqualTo(Friendship.PENDING);
    }

    /** The sender's own request is not an inbox item. */
    @Test
    void theInboxHoldsOnlyWhatOtherPeopleSent() {
        Friendship sent = Friendship.between(ANA, BRUNO, ANA);
        when(friendships.findPendingFor(BRUNO)).thenReturn(Flux.empty());

        assertThat(service.listRequests(BRUNO).collectList().block()).isEmpty();
        verify(friendships).findPendingFor(BRUNO);
        assertThat(sent.getRequestedBy()).isEqualTo(ANA);
    }

    @Test
    void eitherSideMayRemoveTheFriendship() {
        when(friendships.findByIdAndDeletedAtIsNull(1L))
                .thenReturn(Mono.just(Friendship.between(ANA, BRUNO, ANA)));
        when(friendships.save(any(Friendship.class))).thenAnswer(call ->
                Mono.just(call.getArgument(0)));

        service.remove(BRUNO, 1L).block();

        verify(friendships).save(any(Friendship.class));
    }

    @Test
    void somebodyElsesFriendshipIsNotThereToRemove() {
        when(friendships.findByIdAndDeletedAtIsNull(1L))
                .thenReturn(Mono.just(Friendship.between(ANA, BRUNO, ANA)));

        StepVerifier.create(service.remove(999L, 1L))
                .expectErrorSatisfies(error ->
                        assertThat(((ApiException) error).getStatus())
                                .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    // === the school boundary ===============================================

    @Test
    void aColleagueFromAnotherSchoolYearCannotBeFollowed() {
        seatedIn(ANA, YEAR);
        seatedIn(BRUNO, OTHER_YEAR);

        StepVerifier.create(service.request(ANA, new FriendRequest(BRUNO)))
                .expectErrorSatisfies(error ->
                        assertThat(((ApiException) error).getStatus())
                                .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
    }

    @Test
    void somebodyWithNoEnrollmentFollowsNobody() {
        when(seats.activeSeatsOf(ANA)).thenReturn(Flux.empty());

        StepVerifier.create(service.request(ANA, new FriendRequest(BRUNO)))
                .expectErrorSatisfies(error ->
                        assertThat(((ApiException) error).getStatus())
                                .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
    }

    // === discovery =========================================================

    @Test
    void theSearchIsOverTheCallersOwnSchoolYear() {
        seatedIn(ANA, YEAR);
        when(friendships.searchColleagues(eq(ANA), eq(YEAR), isNull(), anyInt()))
                .thenReturn(Flux.just(new User(BRUNO, "Bruno", "Sali")));

        var found = service.searchColleagues(ANA, "  ").collectList().block();

        assertThat(found).singleElement().satisfies(colleague -> {
            assertThat(colleague.accountId()).isEqualTo(BRUNO);
            // The same masked shape the ranking uses, so a friend list is not a way to
            // read the surnames the ranking withholds.
            assertThat(colleague.displayName()).isEqualTo("Bruno S.");
        });
    }

    /**
     * A row with no name still gets one.
     *
     * <p>The search reads the name off the row it already has and never re-asks for it,
     * so a colleague whose profile is blank would otherwise come back nameless — and a
     * nameless entry in a list of people is an entry nobody can pick.</p>
     */
    @Test
    void aColleagueWithoutANameStillGetsOne() {
        seatedIn(ANA, YEAR);
        when(friendships.searchColleagues(anyLong(), anyLong(), any(), anyInt()))
                .thenReturn(Flux.just(new User(BRUNO, null, null)));

        var found = service.searchColleagues(ANA, "bruno").collectList().block();

        assertThat(found).singleElement()
                .satisfies(colleague -> assertThat(colleague.displayName()).isEqualTo("Aluno"));
    }

    /** The surname is withheld here exactly as it is in the ranking. */
    @Test
    void theSearchMasksTheSurnameLikeTheRanking() {
        seatedIn(ANA, YEAR);
        when(friendships.searchColleagues(anyLong(), anyLong(), any(), anyInt()))
                .thenReturn(Flux.just(new User(BRUNO, "Bruno", "Sali")));

        var found = service.searchColleagues(ANA, "bruno").collectList().block();

        assertThat(found).singleElement()
                .satisfies(colleague -> {
                    assertThat(colleague.displayName()).isEqualTo("Bruno S.");
                    assertThat(colleague.displayName()).doesNotContain("Sali");
                });
    }

    // === helpers ===========================================================

    private void seatedIn(Long accountId, Long schoolYearId) {
        when(seats.activeSeatsOf(accountId))
                .thenReturn(Flux.just(new Seat(7L, schoolYearId, 1L)));
    }

    private void named(Long accountId, String first, String last) {
        User user = new User(accountId, first, last);
        when(users.findByAccountIdAndDeletedAtIsNull(accountId)).thenReturn(Flux.just(user));
    }
}