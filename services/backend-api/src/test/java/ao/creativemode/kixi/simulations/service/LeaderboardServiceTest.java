package ao.creativemode.kixi.simulations.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.EnrollmentSeatReader;
import ao.creativemode.kixi.shared.service.EnrollmentSeatReader.Seat;
import ao.creativemode.kixi.simulations.dto.leaderboard.LeaderboardResponse;
import ao.creativemode.kixi.simulations.repository.LeaderboardRepository;
import ao.creativemode.kixi.simulations.repository.LeaderboardRepository.DisplayName;
import ao.creativemode.kixi.simulations.repository.LeaderboardRepository.GroupScore;
import ao.creativemode.kixi.simulations.repository.LeaderboardRepository.Scope;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class LeaderboardServiceTest {

    private static final Long ACCOUNT_ID = 12L;
    private static final Long CLASS_ID = 7L;
    private static final Long FRIEND_ID = 34L;
    private static final Long SCHOOL_ID = 1L;
    private static final Long OTHER_SCHOOL_ID = 2L;
    private static final Long YEAR = 1L;

    private EnrollmentSeatReader seats;
    private LeaderboardRepository repository;
    private LeaderboardService service;

    @BeforeEach
    void setUp() {
        seats = mock(EnrollmentSeatReader.class);
        repository = mock(LeaderboardRepository.class);
        service = new LeaderboardService(seats, repository);

        // Every statement the ranking is asked about exists, unless a test says otherwise.
        // The service looks the id up before it becomes a cache key, so a test that does
        // not stub this gets a null back and a NullPointerException that says nothing
        // about what it was testing.
        when(repository.statementExists(anyLong())).thenReturn(Mono.just(true));
    }

    /** Every account in these tests holds a seat, unless a test takes it away. */
    @BeforeEach
    void everyoneHasASeat() {
        seatedIn(CLASS_ID, SCHOOL_ID, YEAR);
    }

    /** The caller sits in this class of this school for this year, newest seat first. */
    private void seatedIn(Long classId, Long institutionId, Long schoolYearId) {
        when(seats.activeSeatsOf(anyLong()))
                .thenReturn(Flux.just(new Seat(classId, schoolYearId, institutionId)));
    }

    private void noSeat() {
        when(seats.activeSeatsOf(anyLong())).thenReturn(Flux.empty());
    }

    // ── The cohort ──────────────────────────────────────────────────────────

    @Test
    void ranksTheClassTheCallerIsSeatedIn() {
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);

        StepVerifier.create(service.leaderboard(ACCOUNT_ID, "class", null, "all"))
                .expectNextCount(1)
                .verifyComplete();

        verify(repository).countMembers(Scope.CLASS, CLASS_ID, YEAR, SCHOOL_ID);
    }

    @Test
    void ranksTheSchoolTheCallerIsSeatedIn() {
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);

        StepVerifier.create(service.leaderboard(ACCOUNT_ID, "school", null, "all"))
                .expectNextCount(1)
                .verifyComplete();

        verify(repository).countMembers(Scope.SCHOOL, SCHOOL_ID, YEAR, SCHOOL_ID);
    }

    @Test
    void theCohortIsCountedOverTheSchoolYearAndNotOverAllOfThem() {
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);

        one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        // The year travels into both queries and into the cache key. Without it a student
        // who moved keeps their old row ACTIVE and goes on being ranked where they left.
        verify(repository).countMembers(Scope.CLASS, CLASS_ID, YEAR, SCHOOL_ID);
        verify(repository).findAverages(eq(Scope.CLASS), eq(CLASS_ID), eq(YEAR), eq(SCHOOL_ID),
                nullable(Long.class), nullable(LocalDateTime.class), nullable(LocalDateTime.class));
    }

    @Test
    void aSeatInAClassWithNoSchoolStillRanksTheClass() {
        seatedIn(CLASS_ID, null, YEAR);
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);

        StepVerifier.create(service.leaderboard(ACCOUNT_ID, "class", null, "all"))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void refusesAClassRankingWithNoSeat() {
        noSeat();

        StepVerifier.create(service.leaderboard(ACCOUNT_ID, "class", null, "all"))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(error).hasMessageContaining("active enrollment");
                })
                .verify();

        verify(repository, never()).findAverages(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesASchoolRankingWhenTheSeatIsInNoSchool() {
        // The seat exists, so the class ranking is served; the school has nothing to rank.
        seatedIn(CLASS_ID, null, YEAR);
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);

        StepVerifier.create(service.leaderboard(ACCOUNT_ID, "school", null, "all"))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
    }

    @Test
    void takesTheNewestSeatWhenThereIsOneInEachSchool() {
        // One ACTIVE seat per school year is legal, so two schools is a real state. The
        // reader orders them; here we only prove the first is taken as the current one.
        when(seats.activeSeatsOf(anyLong())).thenReturn(Flux.just(
                new Seat(OTHER_CLASS, YEAR, OTHER_SCHOOL_ID),
                new Seat(CLASS_ID, YEAR, SCHOOL_ID)));
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);

        one(service.leaderboard(ACCOUNT_ID, "school", null, "all"));

        verify(repository).countMembers(Scope.SCHOOL, OTHER_SCHOOL_ID, YEAR, OTHER_SCHOOL_ID);
    }

    private static final Long OTHER_CLASS = 9L;

    // ── Privacy ─────────────────────────────────────────────────────────────

    @Test
    void doesNotPublishTheBottomOfAClassWithFewerThanTenStudents() {
        cohortOf(withCaller(descending(8), 0), 8);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.smallGroup()).isTrue();
        assertThat(response.totalStudents()).isEqualTo(8);
        assertThat(response.myPosition()).isEqualTo(1);
        assertThat(positions(response)).containsExactly(1, 2, 3);
        assertThat(positions(response)).doesNotContain(7, 8);
    }

    @Test
    void aSmallClassNamesNobodyWhenTheCallerHasNoScore() {
        cohortOf(descending(8), 8);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.myPosition()).isNull();
        assertThat(response.entries()).isEmpty();
    }

    @Test
    void aSmallClassNeverShowsItsLastPlacesToSomebodyNearTheBottom() {
        cohortOf(withCaller(descending(8), 5), 8); // sixth of eight

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.myPosition()).isEqualTo(6);
        assertThat(positions(response)).containsExactly(4, 5, 6);
        assertThat(positions(response)).doesNotContain(7, 8);
    }

    @Test
    void theCallerStillSeesTheirOwnPlaceAtTheBottomOfASmallClass() {
        cohortOf(withCaller(descending(8), 7), 8);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.myPosition()).isEqualTo(8);
        assertThat(response.entries())
                .filteredOn(entry -> entry.me())
                .singleElement()
                .satisfies(entry -> assertThat(entry.position()).isEqualTo(8));
    }

    /**
     * Eight enrolled, five marked. The floor has to follow the marked rows, or a caller
     * in third place is handed fourth and fifth — and {@code rankedStudents} tells them
     * the fifth is the last one standing.
     */
    @Test
    void theProtectionFollowsTheRowsThatHaveAScoreNotTheEnrolmentCount() {
        cohortOf(withCaller(fiveOfEight(), 2), 8); // third of five marked

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.smallGroup()).isTrue();
        assertThat(response.totalStudents()).isEqualTo(8);
        assertThat(response.rankedStudents()).isEqualTo(5);
        assertThat(response.myPosition()).isEqualTo(3);
        // Floor is 5 - 2 = 3, so fourth and fifth are withheld.
        assertThat(positions(response)).containsExactly(1, 2, 3);
        assertThat(positions(response)).doesNotContain(4, 5);
    }

    @Test
    void aClassWithFewMarkedButManyEnrolledCountsAsSmall() {
        cohortOf(withCaller(fiveOfTwelve(), 2), 12);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.smallGroup()).isTrue();
        assertThat(positions(response)).containsExactly(1, 2, 3);
    }

    @Test
    void doesNotHandOutTheAccountIdOfAColleague() {
        cohortOf(withCaller(descending(12), 4), 12);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.entries())
                .filteredOn(entry -> !entry.me())
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.accountId()).isNull());
    }

    @Test
    void theResponseCarriesNoOtherTraceOfAColleague() throws Exception {
        cohortOf(withCaller(descending(12), 4), 12);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(
                one(service.leaderboard(ACCOUNT_ID, "class", null, "all")));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object row : mapper.readTree(json).path("entries")) {
            rows.add(mapper.convertValue(row, Map.class));
        }
        assertThat(rows).isNotEmpty();
        // A position, a masked label and an average. Nothing else: no account id, no
        // email, no username, no photo, no school of their own.
        assertThat(rows).allSatisfy(row ->
                assertThat(row.keySet())
                        .containsExactlyInAnyOrder("position", "accountId", "displayName", "average", "me"));
        assertThat(rows).filteredOn(row -> row.get("accountId") != null)
                .singleElement()
                .satisfies(row -> assertThat(row.get("me")).isEqualTo(true));
    }

    // ── The boundary between protected and not ─────────────────────────────

    @Test
    void nineStudentsIsStillASmallGroupAndGetsNoPodium() {
        cohortOf(descending(9), 9);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.smallGroup()).isTrue();
        assertThat(response.entries()).isEmpty();
    }

    @Test
    void exactlyTenStudentsGetsThePodium() {
        cohortOf(descending(10), 10);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.smallGroup()).isFalse();
        assertThat(positions(response)).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void tenStudentsIsBigEnoughThatTheBottomIsNotProtected() {
        cohortOf(withCaller(descending(10), 8), 10); // ninth of ten

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.myPosition()).isEqualTo(9);
        assertThat(positions(response)).contains(8, 9, 10);
    }

    @Test
    void aLargeClassIsNotCutOffAtTheBottom() {
        cohortOf(withCaller(descending(30), 28), 30);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.myPosition()).isEqualTo(29);
        assertThat(positions(response)).contains(28, 29, 30);
    }

    @Test
    void publishesThePodiumOnceTheGroupHasTenStudents() {
        cohortOf(descending(12), 12);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(positions(response)).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void givesTheCallerTheirNeighboursPlusThePodiumInALargeGroup() {
        cohortOf(withCaller(descending(30), 14), 30); // fifteenth

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.myPosition()).isEqualTo(15);
        assertThat(positions(response)).containsExactly(1, 2, 3, 4, 5, 13, 14, 15, 16, 17);
    }

    // ── Ordering ────────────────────────────────────────────────────────────

    @Test
    void tiesShareAPositionTheWayARaceDoes() {
        cohortOf(List.of(
                score(101L, 90.0), score(102L, 80.0), score(103L, 80.0),
                score(ACCOUNT_ID, 70.0),
                score(104L, 60.0), score(105L, 50.0), score(106L, 40.0),
                score(107L, 30.0), score(108L, 20.0), score(109L, 10.0),
                score(110L, 5.0)), 12);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        // Twelve ranked rows, so the podium is published: 1 to 5, plus the caller in
        // fourth and the two neighbours below them.
        assertThat(positions(response)).containsExactly(1, 2, 2, 4, 5, 6);
        assertThat(response.myPosition()).isEqualTo(4);
    }

    @Test
    void theSameAveragesRankTheSameWayWhicheverOrderTheyArrive() {
        cohortOf(List.of(
                score(101L, 70.0), score(102L, 90.0), score(ACCOUNT_ID, 80.0), score(104L, 90.0)), 12);
        LeaderboardResponse first = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        service = new LeaderboardService(seats, repository);
        cohortOf(List.of(
                score(104L, 90.0), score(ACCOUNT_ID, 80.0), score(101L, 70.0), score(102L, 90.0)), 12);
        LeaderboardResponse second = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(first.myPosition()).isEqualTo(3);
        assertThat(second.myPosition()).isEqualTo(first.myPosition());
        assertThat(positions(first)).isEqualTo(positions(second));
    }

    // ── The cache ───────────────────────────────────────────────────────────

    @Test
    void readsTheCohortOnceAndStillGivesEachCallerTheirOwnWindow() {
        List<GroupScore> scores = withCaller(descending(30), 14);
        scores.set(29, score(30L, 70.0));
        cohortOf(scores, 30);

        LeaderboardResponse mine = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));
        LeaderboardResponse theirs = one(service.leaderboard(30L, "class", null, "all"));

        // One read of the cohort...
        verify(repository, times(1)).findAverages(any(), any(), any(), any(), any(), any(), any());
        // ...but two different neighbourhoods.
        assertThat(mine.myPosition()).isEqualTo(15);
        assertThat(theirs.myPosition()).isEqualTo(30);
        assertThat(positions(mine)).contains(13, 14, 15, 16, 17).doesNotContain(30);
        assertThat(positions(theirs)).contains(28, 29, 30).doesNotContain(15);
    }

    @Test
    void theCacheKeepsClassAndSchoolApartEvenWithTheSameNumber() {
        // Class ids and institution ids are separate sequences and can hold the same
        // number. The scope is the only thing telling the two rankings apart.
        seatedIn(SCHOOL_ID, SCHOOL_ID, YEAR);
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);

        one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));
        one(service.leaderboard(ACCOUNT_ID, "school", null, "all"));

        verify(repository).countMembers(Scope.CLASS, SCHOOL_ID, YEAR, SCHOOL_ID);
        verify(repository).countMembers(Scope.SCHOOL, SCHOOL_ID, YEAR, SCHOOL_ID);
        verify(repository, times(2)).findAverages(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theCacheKeepsSchoolYearsApart() {
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);

        one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));
        seatedIn(CLASS_ID, SCHOOL_ID, 2L); // same class, another year
        one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        // Two years, two rankings: last year's average is not this year's result.
        verify(repository, times(2)).findAverages(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theCacheKeepsPeriodsAndStatementsApart() {
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);

        one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));
        one(service.leaderboard(ACCOUNT_ID, "class", null, "month"));
        one(service.leaderboard(ACCOUNT_ID, "class", 42L, "all"));
        one(service.leaderboard(ACCOUNT_ID, "class", 42L, "month"));

        verify(repository, times(4)).findAverages(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aDatabaseBlipIsNotRememberedForTheWholeCohort() {
        when(repository.findAverages(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Flux.error(new IllegalStateException("connection reset")));
        when(repository.countMembers(any(), any(), any(), any())).thenReturn(Mono.just(1L));

        for (int attempt = 0; attempt < 3; attempt++) {
            StepVerifier.create(service.leaderboard(ACCOUNT_ID, "class", null, "all"))
                    .expectError(IllegalStateException.class)
                    .verify();
        }

        // cache() holds the error as well as the value. If the key survived, one reset of
        // the connection would answer this class with the same failure for 45 seconds.
        verify(repository, times(3)).findAverages(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aCallerVaryingTheStatementCannotPushAnotherCohortOutOfTheCache() {
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);

        one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));
        for (long statement = 1; statement <= 600; statement++) {
            one(service.leaderboard(ACCOUNT_ID, "class", statement, "all"));
        }

        long readsSoFar = averages();
        one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(averages()).isEqualTo(readsSoFar);
    }

    // ── The parts not built yet ─────────────────────────────────────────────

    @Test
    void theFriendsScopeRanksTheFriendsOfTheCaller() {
        cohortOf(List.of(score(ACCOUNT_ID, 90.0), score(FRIEND_ID, 70.0)), 2);

        LeaderboardResponse response =
                one(service.leaderboard(ACCOUNT_ID, "friends", null, "all"));

        assertThat(response.scope()).isEqualTo("friends");
        assertThat(response.entries()).hasSize(2);
        // The group id handed to the repository is the caller's own account: a friendship
        // is a pair of ids, and there is no column on enrollments that could carry it.
        verify(repository).findAverages(
                eq(Scope.FRIENDS), eq(ACCOUNT_ID), any(), any(), any(), any(), any());
    }

    /**
     * A group of two friends is still readable.
     *
     * <p>The small-group floor would hide every row of a two-person table, and the
     * acceptance criterion of issue #121 is that the two of them appear to each other.
     * Mutual consent is what the floor was standing in for, and here it was given.</p>
     */
    @Test
    void twoFriendsStillSeeEachOther() {
        cohortOf(List.of(score(ACCOUNT_ID, 90.0), score(FRIEND_ID, 70.0)), 2);

        LeaderboardResponse response =
                one(service.leaderboard(ACCOUNT_ID, "friends", null, "all"));

        assertThat(response.entries()).hasSize(2);
        assertThat(response.entries()).anySatisfy(row -> {
            assertThat(row.position()).isEqualTo(2);
            assertThat(row.displayName()).isNotBlank();
        });
    }

    /**
     * The floor still applies to a class of the same size.
     *
     * <p>The exception is for friends, not for small tables: a class of two is the case
     * the rule was written for.</p>
     */
    @Test
    void theSmallGroupFloorStillAppliesToAClass() {
        cohortOf(List.of(score(ACCOUNT_ID, 90.0), score(FRIEND_ID, 70.0)), 2);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.entries()).noneSatisfy(row ->
                assertThat(row.position()).isEqualTo(2));
    }

    /** A student with no seat has no school year, and friends are counted over it. */
    @Test
    void theFriendsScopeNeedsAnActiveEnrollment() {
        noSeat();

        StepVerifier.create(service.leaderboard(ACCOUNT_ID, "friends", null, "all"))
                .expectErrorSatisfies(error ->
                        assertThat(((ApiException) error).getStatus())
                                .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
    }

    @Test
    void rejectsAScopeOrPeriodItCannotServe() {
        StepVerifier.create(service.leaderboard(ACCOUNT_ID, "country", null, "all"))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();

        StepVerifier.create(service.leaderboard(ACCOUNT_ID, "class", null, "term"))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
    }

    @Test
    void passesTheStatementThroughSoOnePaperCanBeRankedOnItsOwn() {
        cohortOf(List.of(score(ACCOUNT_ID, 75.0)), 1);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", 42L, "month"));

        assertThat(response.statementId()).isEqualTo(42L);
        assertThat(response.period()).isEqualTo("month");
    }

    /**
     * A statement id that names nothing is a 404, and never reaches the database twice.
     *
     * <p>It has to be checked before the id becomes a cache key: otherwise a caller varying
     * the id takes a slot out of a cache shared with every real cohort, and gets an empty
     * ranking remembered for the whole TTL.</p>
     */
    @Test
    void aStatementThatDoesNotExistIsNotFoundAndTakesNoCacheSlot() {
        when(repository.statementExists(99L)).thenReturn(Mono.just(false));

        assertThatThrownBy(() -> one(service.leaderboard(ACCOUNT_ID, "class", 99L, "month")))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        // Nothing was ranked, so nothing was cached and no averages were computed.
        verify(repository, never()).findAverages(
                any(), any(), any(), any(), any(), any(), any());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void cohortOf(List<GroupScore> scores, long totalStudents) {
        when(repository.findAverages(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Flux.fromIterable(scores));
        when(repository.countMembers(any(), any(), any(), any())).thenReturn(Mono.just(totalStudents));
        when(repository.findDisplayNames(any())).thenAnswer(call -> {
            Collection<Long> accounts = call.getArgument(0);
            if (accounts == null) {
                return Flux.empty();
            }
            return Flux.fromIterable(accounts.stream()
                    .map(account -> new DisplayName(account, "Rita", "Neto"))
                    .toList());
        });
    }

    private long averages() {
        return org.mockito.Mockito.mockingDetails(repository).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("findAverages"))
                .count();
    }

    /** n students on accounts 101..100+n, highest average first. */
    private static List<GroupScore> descending(int students) {
        List<GroupScore> scores = new ArrayList<>();
        for (int i = 1; i <= students; i++) {
            scores.add(score(100L + i, 100.0 - i));
        }
        return scores;
    }

    /** Five ranked rows out of eight enrolled — the participation the issue is about. */
    private static List<GroupScore> fiveOfEight() {
        return new ArrayList<>(List.of(
                score(101L, 90.0), score(102L, 80.0), score(ACCOUNT_ID, 70.0),
                score(103L, 60.0), score(104L, 50.0)));
    }

    /** The same five marked rows, but twelve students are enrolled in the class. */
    private static List<GroupScore> fiveOfTwelve() {
        return new ArrayList<>(List.of(
                score(101L, 90.0), score(102L, 80.0), score(ACCOUNT_ID, 70.0),
                score(103L, 60.0), score(104L, 50.0)));
    }

    /** Puts the caller in the given place without changing the averages around them. */
    private static List<GroupScore> withCaller(List<GroupScore> scores, int index) {
        scores.set(index, score(ACCOUNT_ID, scores.get(index).average()));
        return scores;
    }

    private static GroupScore score(Long accountId, double average) {
        return new GroupScore(accountId, average);
    }

    private static LeaderboardResponse one(Mono<LeaderboardResponse> mono) {
        return mono.block();
    }

    private static List<Integer> positions(LeaderboardResponse response) {
        return response.entries().stream().map(LeaderboardResponse.Entry::position).toList();
    }
}