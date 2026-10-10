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
                    assertThat(error).hasMessageContaining("No active enrollment");
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
        when(meService.getMe(ACCOUNT_ID)).thenReturn(Mono.just(me(null, SCHOOL_ID)));

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
        when(repository.findDisplayNames(any())).thenAnswer(call ->
                Flux.fromIterable(call.<Collection<Long>>getArgument(0).stream()
                        .map(account -> new DisplayName(account, "Rita", "Neto"))
                        .toList()));
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