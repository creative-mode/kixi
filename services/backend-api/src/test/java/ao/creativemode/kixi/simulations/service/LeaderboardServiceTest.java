package ao.creativemode.kixi.simulations.service;

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

import ao.creativemode.kixi.institutions.dto.enrollment.MeResponse;
import ao.creativemode.kixi.institutions.model.Enrollment;
import ao.creativemode.kixi.institutions.repository.EnrollmentRepository;
import ao.creativemode.kixi.institutions.service.MeService;
import ao.creativemode.kixi.shared.exception.ApiException;
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
    private static final Long OTHER_CLASS_ID = 9L;
    private static final Long SCHOOL_ID = 1L;
    private static final Long OTHER_SCHOOL_ID = 2L;

    private MeService meService;
    private EnrollmentRepository enrollments;
    private LeaderboardRepository repository;
    private LeaderboardService service;

    @BeforeEach
    void setUp() {
        meService = mock(MeService.class);
        enrollments = mock(EnrollmentRepository.class);
        repository = mock(LeaderboardRepository.class);
        service = new LeaderboardService(meService, enrollments, repository);
        when(meService.classInstitutionId(CLASS_ID)).thenReturn(Mono.just(SCHOOL_ID));
        when(meService.classInstitutionId(OTHER_CLASS_ID)).thenReturn(Mono.just(OTHER_SCHOOL_ID));
    }

    /** Every account in these tests holds a seat, unless a test takes it away. */
    @BeforeEach
    void everyoneHasASeat() {
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(anyLong()))
                .thenAnswer(call -> Flux.just(enrollment(
                        call.<Long>getArgument(0), CLASS_ID, SCHOOL_ID, false)));
    }

    private static Enrollment enrollment(
            Long accountId, Long classId, Long institutionId, boolean cancelled) {
        Enrollment enrollment = new Enrollment();
        enrollment.setAccountId(accountId);
        enrollment.setClassId(classId);
        enrollment.setSchoolYearId(1L);
        if (cancelled) {
            enrollment.markAsDeleted();
        }
        return enrollment;
    }

    /** An ACTIVE seat with the instant it was joined and the id it got. */
    private static Enrollment enrolledOn(
            Long classId, Long institutionId, LocalDateTime createdAt, Long id) {
        Enrollment enrollment = enrollment(ACCOUNT_ID, classId, institutionId, false);
        enrollment.setId(id);
        enrollment.setCreatedAt(createdAt);
        return enrollment;
    }

    /** A cancelled enrollment: the seat is gone, the profile does not know it. */
    private void cancelledEnrollment() {
        cancelledEnrollment(CLASS_ID, SCHOOL_ID);
    }

    private void cancelledEnrollment(Long classId, Long institutionId) {
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(anyLong()))
                .thenReturn(Flux.just(enrollment(ACCOUNT_ID, classId, institutionId, true)));
    }

    /** No enrollment at all: linked to the school, seated nowhere. */
    private void noEnrollment() {
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(anyLong())).thenReturn(Flux.empty());
    }

    // ── The cohort ──────────────────────────────────────────────────────────

    @Test
    void resolvesTheClassFromMeRatherThanFromTheCaller() {
        enrolled(CLASS_ID);
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 1);

        StepVerifier.create(service.leaderboard(ACCOUNT_ID, "class", null, "all"))
                .expectNextCount(1)
                .verifyComplete();

        verify(repository).findAverages(eq(Scope.CLASS), eq(CLASS_ID), nullable(Long.class),
                nullable(LocalDateTime.class), nullable(LocalDateTime.class));
    }

    @Test
    void resolvesTheSchoolFromMeForTheSchoolScope() {
        enrolled(CLASS_ID);
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 1);

        StepVerifier.create(service.leaderboard(ACCOUNT_ID, "school", null, "all"))
                .expectNextCount(1)
                .verifyComplete();

        verify(repository).countMembers(Scope.SCHOOL, SCHOOL_ID);
    }

    // ── Membership is proved, not assumed ───────────────────────────────────

    @Test
    void aCancelledEnrollmentDoesNotBuyTheRankingOfTheClassItLeft() {
        // /me still reports the class and the school: it picks the most recent enrollment
        // without asking whether it is active, so on its own it would say yes.
        cancelledEnrollment();
        enrolled(CLASS_ID);
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);

        StepVerifier.create(service.leaderboard(ACCOUNT_ID, "class", null, "all"))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(error).hasMessageContaining("active enrollment");
                })
                .verify();

        verify(repository, never()).findAverages(any(), anyLong(), any(), any(), any());
    }

    @Test
    void anInstitutionalLinkIsNotASeatInAnyClass() {
        // Linked to the school, but with no enrollment anywhere inside it: the school
        // ranking counts ACTIVE students only, and this account is not one of them.
        enrolled(CLASS_ID);
        noEnrollment();
        cohortOf(List.of(score(101L, 90.0)), 20);

        StepVerifier.create(service.leaderboard(ACCOUNT_ID, "school", null, "all"))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(error).hasMessageContaining("active enrollment");
                })
                .verify();

        verify(repository, never()).findAverages(any(), anyLong(), any(), any(), any());
    }

    @Test
    void aCancelledSeatInAnotherClassDoesNotRefuseTheValidOne() {
        // The same trap as the school scope, one level down: /me points at the cancelled
        // seat's class, and looking for an active seat in THAT class finds nothing.
        when(meService.getMe(ACCOUNT_ID)).thenReturn(Mono.just(me(OTHER_CLASS_ID, OTHER_SCHOOL_ID)));
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(anyLong()))
                .thenReturn(Flux.just(
                        enrollment(ACCOUNT_ID, OTHER_CLASS_ID, OTHER_SCHOOL_ID, true),
                        enrollment(ACCOUNT_ID, CLASS_ID, SCHOOL_ID, false)));

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        // The class of the ACTIVE seat, never the one the cancelled row pointed at.
        assertThat(response.myPosition()).isEqualTo(1);
        verify(repository).countMembers(Scope.CLASS, CLASS_ID);
        verify(repository, never()).countMembers(Scope.CLASS, OTHER_CLASS_ID);
    }

    @Test
    void aSmallClassNeverShowsItsLastPlacesToSomebodyNearTheBottom() {
        // Eight students, the caller sixth. A symmetric window would hand them seventh and
        // eighth, which is the exposure the issue asks to avoid.
        enrolled(CLASS_ID);
        cohortOf(withCaller(descending(8), 5), 8);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.myPosition()).isEqualTo(6);
        assertThat(positions(response)).containsExactly(4, 5, 6);
        assertThat(positions(response)).doesNotContain(7, 8);
    }

    @Test
    void theCallerStillSeesTheirOwnPlaceAtTheBottomOfASmallClass() {
        // Being last is not something to hide from the student it belongs to.
        enrolled(CLASS_ID);
        cohortOf(withCaller(descending(8), 7), 8);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.myPosition()).isEqualTo(8);
        assertThat(positions(response)).contains(8);
        assertThat(response.entries())
                .filteredOn(entry -> entry.me())
                .singleElement()
                .satisfies(entry -> assertThat(entry.position()).isEqualTo(8));
    }

    @Test
    void aLargeClassIsNotCutOffAtTheBottom() {
        // The protection is for small classes only; thirty students are not a privacy risk.
        enrolled(CLASS_ID);
        cohortOf(withCaller(descending(30), 28), 30);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.myPosition()).isEqualTo(29);
        assertThat(positions(response)).contains(28, 29, 30);
    }

    @Test
    void aCancelledSeatInAnotherSchoolDoesNotRefuseTheValidOne() {
        // /me sorts enrollments by id and not by state, so a newer cancelled row — a late
        // withdrawal from elsewhere — can point the profile at the wrong school. The seat
        // that counts is the ACTIVE one, whichever order the profile read them in.
        // The profile points at OTHER_SCHOOL_ID, the school of the newer cancelled row.
        when(meService.getMe(ACCOUNT_ID)).thenReturn(Mono.just(me(OTHER_CLASS_ID, OTHER_SCHOOL_ID)));
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);
        // The ACTIVE seat in CLASS_ID is the one that counts.
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(anyLong()))
                .thenReturn(Flux.just(
                        enrollment(ACCOUNT_ID, OTHER_CLASS_ID, OTHER_SCHOOL_ID, true),
                        enrollment(ACCOUNT_ID, CLASS_ID, SCHOOL_ID, false)));

        // Served, not refused: the ACTIVE seat is a valid reason on its own, whatever
        // /me reported and whatever school the withdrawn row pointed at.
        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "school", null, "all"));

        assertThat(response.myPosition()).isEqualTo(1);
        verify(repository).countMembers(Scope.SCHOOL, SCHOOL_ID);
        verify(repository, never()).countMembers(Scope.SCHOOL, OTHER_SCHOOL_ID);
    }

    @Test
    void withTwoActiveSeatsInDifferentSchoolsTheNewestOneWins() {
        // Legal state: one ACTIVE enrollment per school year, so a student who moved can
        // hold two in two schools. Which one the ranking follows must be a rule, never the
        // order the database happened to return the rows in.
        when(meService.getMe(ACCOUNT_ID)).thenReturn(Mono.just(me(OTHER_CLASS_ID, OTHER_SCHOOL_ID)));
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(anyLong()))
                .thenReturn(Flux.just(
                        enrolledOn(CLASS_ID, SCHOOL_ID, LocalDateTime.of(2026, 9, 1, 0, 0), 1L),
                        enrolledOn(OTHER_CLASS_ID, OTHER_SCHOOL_ID, LocalDateTime.of(2026, 10, 1, 0, 0), 2L)));

        one(service.leaderboard(ACCOUNT_ID, "school", null, "all"));

        verify(repository).countMembers(Scope.SCHOOL, OTHER_SCHOOL_ID);
        verify(repository, never()).countMembers(Scope.SCHOOL, SCHOOL_ID);
    }

    @Test
    void theSameTwoSeatsGiveTheSameAnswerWhicheverOrderTheyArrive() {
        // The rule has to survive the row order, not just be documented.
        when(meService.getMe(ACCOUNT_ID)).thenReturn(Mono.just(me(OTHER_CLASS_ID, OTHER_SCHOOL_ID)));
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);

        Enrollment older = enrolledOn(CLASS_ID, SCHOOL_ID, LocalDateTime.of(2026, 9, 1, 0, 0), 1L);
        Enrollment newer = enrolledOn(OTHER_CLASS_ID, OTHER_SCHOOL_ID, LocalDateTime.of(2026, 10, 1, 0, 0), 2L);

        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(anyLong()))
                .thenReturn(Flux.just(older, newer));
        one(service.leaderboard(ACCOUNT_ID, "school", null, "all"));
        verify(repository).countMembers(Scope.SCHOOL, OTHER_SCHOOL_ID);

        service = new LeaderboardService(meService, enrollments, repository);
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(anyLong()))
                .thenReturn(Flux.just(newer, older));
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);
        one(service.leaderboard(ACCOUNT_ID, "school", null, "all"));

        // Same seat, so the same school — the cache is cold again, hence the fresh service.
        verify(repository, times(2)).countMembers(Scope.SCHOOL, OTHER_SCHOOL_ID);
        verify(repository, never()).countMembers(Scope.SCHOOL, SCHOOL_ID);
    }

    @Test
    void twoSeatsJoinedInTheSameInstantStillPickOneDeterministically() {
        // created_at has second precision in some setups, so the id is the tiebreaker.
        when(meService.getMe(ACCOUNT_ID)).thenReturn(Mono.just(me(OTHER_CLASS_ID, OTHER_SCHOOL_ID)));
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);
        LocalDateTime sameInstant = LocalDateTime.of(2026, 10, 1, 9, 0);
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(anyLong()))
                .thenReturn(Flux.just(
                        enrolledOn(OTHER_CLASS_ID, OTHER_SCHOOL_ID, sameInstant, 2L),
                        enrolledOn(CLASS_ID, SCHOOL_ID, sameInstant, 1L)));

        one(service.leaderboard(ACCOUNT_ID, "school", null, "all"));

        // Same instant, highest id: OTHER_SCHOOL_ID, and not whichever arrived first.
        verify(repository).countMembers(Scope.SCHOOL, OTHER_SCHOOL_ID);
        verify(repository, never()).countMembers(Scope.SCHOOL, SCHOOL_ID);
    }

    @Test
    void theSchoolComesFromTheSeatAndNotFromAnInstitutionalLinkAlone() {
        // The profile reports a school that the ACTIVE seat is not in; the seat decides.
        enrolled(CLASS_ID);
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(anyLong()))
                .thenReturn(Flux.just(enrollment(ACCOUNT_ID, CLASS_ID, SCHOOL_ID, false)));
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);

        one(service.leaderboard(ACCOUNT_ID, "school", null, "all"));

        verify(repository).countMembers(Scope.SCHOOL, SCHOOL_ID);
    }

    @Test
    void anActiveSeatOpensTheRankingOfThatSchool() {
        enrolled(CLASS_ID);
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);

        StepVerifier.create(service.leaderboard(ACCOUNT_ID, "school", null, "all"))
                .expectNextCount(1)
                .verifyComplete();
    }

    // ── Privacy ─────────────────────────────────────────────────────────────

    @Test
    void doesNotHandOutTheAccountIdOfAColleague() {
        enrolled(CLASS_ID);
        cohortOf(withCaller(descending(12), 4), 12);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.entries())
                .filteredOn(entry -> entry.me())
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.accountId()).isEqualTo(ACCOUNT_ID));
        // The masked name without the id is all the client needs to render a row, and the
        // id is what would let someone match a row back to a person across the API.
        assertThat(response.entries())
                .filteredOn(entry -> !entry.me())
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.accountId()).isNull());
    }

    @Test
    void refusesAClassRankingForAnAccountWithNoEnrollment() {
        // The profile has no class because there is no seat to read one from, and a link
        // without a seat cannot stand in for it.
        when(meService.getMe(ACCOUNT_ID)).thenReturn(Mono.just(me(null, SCHOOL_ID)));
        noEnrollment();

        StepVerifier.create(service.leaderboard(ACCOUNT_ID, "class", null, "all"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                })
                .verify();
    }

    @Test
    void refusesASchoolRankingForAnAccountSeatedNowhere() {
        // The profile reports no school because there is no enrollment to read one from,
        // and a link without a seat cannot stand in for it.
        when(meService.getMe(ACCOUNT_ID)).thenReturn(Mono.just(me(CLASS_ID, null)));
        noEnrollment();

        StepVerifier.create(service.leaderboard(ACCOUNT_ID, "school", null, "all"))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
    }

    // ── Privacy ─────────────────────────────────────────────────────────────

    @Test
    void doesNotPublishTheBottomOfAClassWithFewerThanTenStudents() {
        enrolled(CLASS_ID);
        // Eight students, the caller first. Only the caller's neighbours may travel.
        cohortOf(withCaller(descending(8), 0), 8);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.smallGroup()).isTrue();
        assertThat(response.totalStudents()).isEqualTo(8);
        assertThat(response.myPosition()).isEqualTo(1);
        assertThat(positions(response)).containsExactly(1, 2, 3);
        // The criterion of the issue, stated as a test: nobody at the bottom is named.
        assertThat(positions(response)).doesNotContain(7, 8);
    }

    @Test
    void aSmallClassNamesNobodyWhenTheCallerHasNoScore() {
        enrolled(CLASS_ID);
        cohortOf(descending(8), 8); // the caller is not in the ranking

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.myPosition()).isNull();
        assertThat(response.entries()).isEmpty();
    }

    @Test
    void publishesThePodiumOnceTheGroupHasTenStudents() {
        enrolled(CLASS_ID);
        cohortOf(descending(12), 12);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.smallGroup()).isFalse();
        assertThat(positions(response)).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void givesTheCallerTheirNeighboursPlusThePodiumInALargeGroup() {
        enrolled(CLASS_ID);
        cohortOf(withCaller(descending(30), 14), 30); // the caller is fifteenth

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.myPosition()).isEqualTo(15);
        // 1-5 is the podium, 13-17 is the caller's neighbourhood.
        assertThat(positions(response)).containsExactly(1, 2, 3, 4, 5, 13, 14, 15, 16, 17);
    }

    @Test
    void masksTheNameOfEveryoneElse() {
        enrolled(CLASS_ID);
        cohortOf(withCaller(descending(4), 0), 4);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.entries())
                .allSatisfy(entry -> assertThat(entry.displayName()).isEqualTo("Rita N."));
    }

    // ── Ordering ────────────────────────────────────────────────────────────

    @Test
    void tiesShareAPositionTheWayARaceDoes() {
        enrolled(CLASS_ID);
        // Twelve students so there is a podium to show the tie inside. The caller's
        // neighbourhood is 2..6 and the podium is 1..5, so the two overlap.
        cohortOf(List.of(
                score(101L, 90.0),
                score(102L, 80.0),
                score(103L, 80.0),
                score(ACCOUNT_ID, 70.0),
                score(104L, 60.0),
                score(105L, 50.0),
                score(106L, 40.0),
                score(107L, 30.0)), 12);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(positions(response)).containsExactly(1, 2, 2, 4, 5, 6);
        assertThat(response.myPosition()).isEqualTo(4);
    }

    // ── The cache ───────────────────────────────────────────────────────────

    @Test
    void readsTheCohortOnceAndStillGivesEachCallerTheirOwnWindow() {
        enrolled(CLASS_ID);
        // The caller is fifteenth; account 30 is the one sitting at the bottom.
        List<GroupScore> scores = withCaller(descending(30), 14);
        scores.set(29, score(30L, 70.0));
        cohortOf(scores, 30);

        LeaderboardResponse mine = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));
        LeaderboardResponse theirs = one(service.leaderboard(30L, "class", null, "all"));

        // One read of the cohort...
        verify(repository, times(1)).findAverages(any(), anyLong(), nullable(Long.class),
                nullable(LocalDateTime.class), nullable(LocalDateTime.class));
        // ...but two different neighbourhoods, because the window is built per caller.
        assertThat(mine.myPosition()).isEqualTo(15);
        assertThat(theirs.myPosition()).isEqualTo(30);
        assertThat(positions(mine)).contains(13, 14, 15, 16, 17);
        assertThat(positions(theirs)).contains(28, 29, 30);
        assertThat(positions(theirs)).doesNotContain(15);
    }

    // ── The cache under stress ──────────────────────────────────────────────

    @Test
    void aDatabaseBlipIsNotRememberedForTheWholeCohort() {
        enrolled(CLASS_ID);
        when(repository.findAverages(any(), anyLong(), nullable(Long.class),
                nullable(LocalDateTime.class), nullable(LocalDateTime.class)))
                .thenReturn(Flux.error(new IllegalStateException("connection reset")));
        when(repository.countMembers(any(), anyLong())).thenReturn(Mono.just(1L));

        for (int attempt = 0; attempt < 3; attempt++) {
            StepVerifier.create(service.leaderboard(ACCOUNT_ID, "class", null, "all"))
                    .expectError(IllegalStateException.class)
                    .verify();
        }

        // cache() holds the error as well as the value. If the key survived, one reset of
        // the connection would answer this class with the same failure for 45 seconds.
        verify(repository, times(3)).findAverages(any(), anyLong(), nullable(Long.class),
                nullable(LocalDateTime.class), nullable(LocalDateTime.class));
    }

    @Test
    void aCallerVaryingTheStatementCannotPushAnotherCohortOutOfTheCache() {
        enrolled(CLASS_ID);
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 1);

        // The ranking with no statement filter, which is the one worth protecting.
        one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));
        for (long statement = 1; statement <= 600; statement++) {
            one(service.leaderboard(ACCOUNT_ID, "class", statement, "all"));
        }

        long readsSoFar = averages();
        one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        // Still served from the cache: flooding the map with variants never reaches a
        // ranking that somebody else is relying on.
        assertThat(averages()).isEqualTo(readsSoFar);
    }

    // ── The parts not built yet ─────────────────────────────────────────────

    @Test
    void theFriendsScopeIsNotFoundUntilFriendshipsExist() {
        StepVerifier.create(service.leaderboard(ACCOUNT_ID, "friends", null, "all"))
                .expectErrorSatisfies(error -> {
                    assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error).hasMessageContaining("#121");
                })
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

    // ── The boundary between protected and not ─────────────────────────────

    @Test
    void nineStudentsIsStillASmallGroupAndGetsNoPodium() {
        enrolled(CLASS_ID);
        cohortOf(descending(9), 9);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.smallGroup()).isTrue();
        // Unranked caller, so the only source of entries would be the podium.
        assertThat(response.entries()).isEmpty();
    }

    @Test
    void nineStudentsStillProtectsTheBottomForACallerNearIt() {
        enrolled(CLASS_ID);
        cohortOf(withCaller(descending(9), 6), 9); // seventh of nine

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.myPosition()).isEqualTo(7);
        assertThat(positions(response)).containsExactly(5, 6, 7);
        assertThat(positions(response)).doesNotContain(8, 9);
    }

    @Test
    void exactlyTenStudentsGetsThePodium() {
        // The limit itself: ten is not a small group, so the top is published.
        enrolled(CLASS_ID);
        cohortOf(descending(10), 10);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.smallGroup()).isFalse();
        assertThat(positions(response)).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void tenStudentsIsBigEnoughThatTheBottomIsNotProtected() {
        enrolled(CLASS_ID);
        cohortOf(withCaller(descending(10), 8), 10); // ninth of ten

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.myPosition()).isEqualTo(9);
        assertThat(positions(response)).contains(8, 9, 10);
    }

    // ── Nothing else in the payload identifies a colleague ─────────────────

    @Test
    void theResponseCarriesNoOtherTraceOfAColleague() throws Exception {
        enrolled(CLASS_ID);
        cohortOf(withCaller(descending(12), 4), 12);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(
                one(service.leaderboard(ACCOUNT_ID, "class", null, "all")));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object row : mapper.readTree(json).path("entries")) {
            rows.add(mapper.convertValue(row, Map.class));
        }
        assertThat(rows).isNotEmpty();
        // Colleagues appear as a position, a masked label and an average. Nothing else:
        // no account id, no email, no username, no photo, no institution of their own.
        assertThat(rows).allSatisfy(row ->
                assertThat(row.keySet())
                        .containsExactlyInAnyOrder("position", "accountId", "displayName", "average", "me"));
        // The only account id in the payload is the caller's own, on their own row.
        assertThat(rows).filteredOn(row -> row.get("accountId") != null)
                .singleElement()
                .satisfies(row -> assertThat(row.get("me")).isEqualTo(true));
    }

    // ── The ranking does not depend on how the rows arrived ────────────────

    @Test
    void theSameAveragesRankTheSameWayWhicheverOrderTheyArrive() {
        // 90, 90 tie for first; the caller on 80 is third either way round.
        List<GroupScore> forwards = List.of(
                score(101L, 70.0), score(102L, 90.0), score(ACCOUNT_ID, 80.0), score(104L, 90.0));

        enrolled(CLASS_ID);
        cohortOf(forwards, 12);
        LeaderboardResponse first = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        service = new LeaderboardService(meService, enrollments, repository);
        cohortOf(List.of(score(104L, 90.0), score(ACCOUNT_ID, 80.0), score(101L, 70.0), score(102L, 90.0)), 12);
        LeaderboardResponse second = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(first.myPosition()).isEqualTo(3);
        assertThat(second.myPosition()).isEqualTo(first.myPosition());
        // And the two tied students hold first in both, never swapping places.
        assertThat(positions(first)).isEqualTo(positions(second));
        assertThat(first.entries())
                .filteredOn(entry -> !entry.me())
                .extracting(LeaderboardResponse.Entry::position)
                .contains(1, 1);
    }

    @Test
    void tiedAccountsAlwaysGetTheSameOrderFromTheSameAverages() {
        // Two students on exactly 90.0 and a caller behind them: the tie must not flip
        // between the two tied rows, or the caller's own position would wobble.
        List<GroupScore> scores = List.of(
                score(102L, 90.0), score(101L, 90.0), score(ACCOUNT_ID, 50.0));

        enrolled(CLASS_ID);
        cohortOf(scores, 12);
        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));

        assertThat(response.myPosition()).isEqualTo(3);
        assertThat(response.entries())
                .filteredOn(entry -> !entry.me())
                .extracting(LeaderboardResponse.Entry::position)
                .containsExactly(1, 1);
    }

    // ── The cache never crosses a boundary ─────────────────────────────────

    @Test
    void theCacheKeepsClassAndSchoolApartEvenWithTheSameNumber() {
        // A class id and an institution id are separate sequences and CAN hold the same
        // number. Seated in class 1 of school 1, the scope is the only thing telling the
        // two rankings apart, so a cache keyed on the number alone would serve one for the
        // other — the class ranking handed to someone asking for the school.
        Long sharedNumber = SCHOOL_ID;
        enrolled(sharedNumber);
        // The seat really is in class 1 of school 1, so both scopes rank group 1.
        when(enrollments.findAllByAccountIdAndDeletedAtIsNull(anyLong()))
                .thenReturn(Flux.just(enrollment(ACCOUNT_ID, sharedNumber, sharedNumber, false)));
        when(meService.classInstitutionId(sharedNumber)).thenReturn(Mono.just(sharedNumber));
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);

        one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));
        one(service.leaderboard(ACCOUNT_ID, "school", null, "all"));

        verify(repository).countMembers(Scope.CLASS, sharedNumber);
        verify(repository).countMembers(Scope.SCHOOL, sharedNumber);
        verify(repository, times(2)).findAverages(any(), any(), any(), any(), any());
    }

    @Test
    void theCacheKeepsPeriodsAndStatementsApart() {
        enrolled(CLASS_ID);
        cohortOf(List.of(score(ACCOUNT_ID, 90.0)), 12);

        one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));
        one(service.leaderboard(ACCOUNT_ID, "class", null, "month"));
        one(service.leaderboard(ACCOUNT_ID, "class", 42L, "all"));
        one(service.leaderboard(ACCOUNT_ID, "class", 42L, "month"));

        // Four different questions, four different rankings: none of them is an answer
        // to a question that was not asked.
        verify(repository, times(4)).findAverages(any(), any(), any(), any(), any());
    }

    @Test
    void theCacheServesAWindowButNeverTheOtherCallersAnswer() {
        // Two callers, same cohort, same cached ranking — two different neighbourhoods,
        // and neither window carries the other's rows.
        enrolled(CLASS_ID);
        List<GroupScore> scores = withCaller(descending(30), 14);
        scores.set(29, score(30L, 70.0));
        cohortOf(scores, 30);

        LeaderboardResponse mine = one(service.leaderboard(ACCOUNT_ID, "class", null, "all"));
        LeaderboardResponse theirs = one(service.leaderboard(30L, "class", null, "all"));

        assertThat(positions(mine)).contains(13, 14, 15, 16, 17).doesNotContain(30);
        assertThat(positions(theirs)).contains(28, 29, 30).doesNotContain(15);
        // Only one read of the cohort behind both.
        verify(repository, times(1)).findAverages(any(), anyLong(), nullable(Long.class),
                nullable(LocalDateTime.class), nullable(LocalDateTime.class));
    }

    @Test
    void passesTheStatementThroughSoOnePaperCanBeRankedOnItsOwn() {
        enrolled(CLASS_ID);
        cohortOf(List.of(score(ACCOUNT_ID, 75.0)), 1);

        LeaderboardResponse response = one(service.leaderboard(ACCOUNT_ID, "class", 42L, "month"));

        assertThat(response.statementId()).isEqualTo(42L);
        assertThat(response.period()).isEqualTo("month");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Any signed-in account here belongs to the same class; several callers test the cache. */
    private void enrolled(Long classId) {
        when(meService.getMe(anyLong())).thenReturn(Mono.just(me(classId, SCHOOL_ID)));
    }

    private void cohortOf(List<GroupScore> scores, long totalStudents) {
        when(repository.findAverages(any(), anyLong(), nullable(Long.class),
                nullable(LocalDateTime.class), nullable(LocalDateTime.class)))
                .thenReturn(Flux.fromIterable(scores));
        when(repository.countMembers(any(), anyLong())).thenReturn(Mono.just(totalStudents));
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

    /** n students on accounts 101..100+n, highest average first. */
    private static List<GroupScore> descending(int students) {
        List<GroupScore> scores = new ArrayList<>();
        for (int i = 1; i <= students; i++) {
            scores.add(score(100L + i, 100.0 - i));
        }
        return scores;
    }

    /** Puts the caller in the given place without changing the averages around them. */
    private static List<GroupScore> withCaller(List<GroupScore> scores, int index) {
        scores.set(index, score(ACCOUNT_ID, scores.get(index).average()));
        return scores;
    }

    private static GroupScore score(Long accountId, double average) {
        return new GroupScore(accountId, average);
    }

    /** How many times the ranking was actually read from the database. */
    private long averages() {
        return org.mockito.Mockito.mockingDetails(repository).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("findAverages"))
                .count();
    }

    private static MeResponse me(Long classId, Long schoolId) {
        return new MeResponse(
                ACCOUNT_ID, "aluno", "aluno@kixi.ao", "Ana", "Manuel", null, List.of("STUDENT"),
                schoolId == null ? null : new MeResponse.SchoolInfo(schoolId, "ITEL", "ITEL"),
                null,
                classId == null ? null
                        : new MeResponse.ClassInfo(classId, "10A", 10, 1L, "2024/2025"),
                false);
    }

    private static LeaderboardResponse one(Mono<LeaderboardResponse> mono) {
        return mono.block();
    }

    private static List<Integer> positions(LeaderboardResponse response) {
        return response.entries().stream().map(LeaderboardResponse.Entry::position).toList();
    }
}