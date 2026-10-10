package ao.creativemode.kixi.simulations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.institutions.dto.enrollment.MeResponse;
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
    private static final Long SCHOOL_ID = 1L;

    private MeService meService;
    private LeaderboardRepository repository;
    private LeaderboardService service;

    @BeforeEach
    void setUp() {
        meService = mock(MeService.class);
        repository = mock(LeaderboardRepository.class);
        service = new LeaderboardService(meService, repository);
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
    void refusesASchoolRankingForAnAccountWithNoAffiliation() {
        when(meService.getMe(ACCOUNT_ID)).thenReturn(Mono.just(me(CLASS_ID, null)));

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

    private static MeResponse me(Long classId, Long schoolId) {
        return new MeResponse(
                ACCOUNT_ID, "aluno", "aluno@kixi.ao", "Ana", "Manuel", null, List.of("STUDENT"),
                schoolId == null ? null : new MeResponse.SchoolInfo(schoolId, "ITEL", "ITEL"),
                null,
                classId == null ? null
                        : new MeResponse.ClassInfo(classId, "10A", 10, 1L, "2024/2025"));
    }

    private static LeaderboardResponse one(Mono<LeaderboardResponse> mono) {
        return mono.block();
    }

    private static List<Integer> positions(LeaderboardResponse response) {
        return response.entries().stream().map(LeaderboardResponse.Entry::position).toList();
    }
}