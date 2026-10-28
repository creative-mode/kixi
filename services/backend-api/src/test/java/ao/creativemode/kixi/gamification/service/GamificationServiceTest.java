package ao.creativemode.kixi.gamification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ao.creativemode.kixi.gamification.model.UserStats;
import ao.creativemode.kixi.gamification.repository.BadgeRepository;
import ao.creativemode.kixi.gamification.repository.GamificationRepository;
import ao.creativemode.kixi.gamification.repository.UserBadgeRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GamificationServiceTest {
    private GamificationRepository repository;
    private BadgeRepository badgeRepository;
    private UserBadgeRepository userBadgeRepository;
    private GamificationService service;
    private UserStats userStats;

    @BeforeEach
    void setUp() {
        repository = mock(GamificationRepository.class);
        badgeRepository = mock(BadgeRepository.class);
        userBadgeRepository = mock(UserBadgeRepository.class);
        service = new GamificationService(repository, badgeRepository, userBadgeRepository,
                Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneId.of("UTC")));
        userStats = stats(7L, 0L, 1, 0, 1, null);
        when(repository.ensureStats(7L)).thenReturn(Mono.just(1));
        when(repository.findByAccountId(7L)).thenReturn(Mono.just(userStats));
        when(repository.lockByAccountId(7L)).thenReturn(Mono.just(userStats));
        when(repository.findSimulationAward(10L)).thenReturn(Mono.empty());
        when(repository.saveSimulationAward(eq(10L), eq(7L), nullable(Long.class), any(), any(), any()))
                .thenReturn(Mono.just(1));
        when(repository.save(any(UserStats.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(repository.unlockBadge(eq(7L), anyString(), nullable(Long.class), any())).thenReturn(Mono.just(1));
        when(repository.findLatestTopicScores(7L, 4L)).thenReturn(Flux.just(85.0, 75.0, 80.0));
    }

    @Test
    void awardsScoreAndTimeXpAndUnlocksFirstAndMasteryBadges() {
        StepVerifier.create(service.simulationFinished(10L, 7L, 4L, 80, 100, 1_260,
                        LocalDateTime.parse("2026-10-11T11:00:00"), true))
                .verifyComplete();

        assertThat(userStats.getXp()).isEqualTo(100L);
        assertThat(userStats.getLevel()).isEqualTo(2);
        assertThat(userStats.getStreak()).isEqualTo(1);
        assertThat(userStats.getLastActivityDate()).isEqualTo(LocalDate.parse("2026-10-11"));
        verify(repository).unlockBadge(7L, "FIRST_SIMULATION", null,
                LocalDateTime.parse("2026-10-11T12:00:00"));
        verify(repository).unlockBadge(7L, "TOPIC_MASTERED", 4L,
                LocalDateTime.parse("2026-10-11T12:00:00"));
    }

    @Test
    void spendsOneFreePauseToPreserveAStreakAcrossOneMissedDay() {
        userStats.setStreak(5);
        userStats.setLongestStreak(5);
        userStats.setLastActivityDate(LocalDate.parse("2026-10-08"));

        StepVerifier.create(service.simulationFinished(10L, 7L, null, 50, 100, 0,
                        LocalDateTime.parse("2026-10-10T10:00:00"), true))
                .verifyComplete();

        assertThat(userStats.getStreak()).isEqualTo(7);
        assertThat(userStats.getLongestStreak()).isEqualTo(7);
        assertThat(userStats.getFreePauses()).isZero();
        assertThat(userStats.getLastActivityDate()).isEqualTo(LocalDate.parse("2026-10-10"));
    }

    @Test
    void duplicateAwardDoesNotCountStudyDayTwice() {
        userStats.setXp(100L);
        userStats.setStreak(3);
        userStats.setLastActivityDate(LocalDate.parse("2026-10-10"));
        when(repository.findSimulationAward(10L)).thenReturn(Mono.just(100));

        StepVerifier.create(service.simulationFinished(10L, 7L, null, 80, 100, 1_200,
                        LocalDateTime.parse("2026-10-11T10:00:00"), true))
                .verifyComplete();

        assertThat(userStats.getXp()).isEqualTo(100L);
        assertThat(userStats.getStreak()).isEqualTo(3);
        assertThat(userStats.getLastActivityDate()).isEqualTo(LocalDate.parse("2026-10-10"));
        verify(repository, never()).unlockBadge(7L, "FIRST_SIMULATION", null,
                LocalDateTime.parse("2026-10-11T12:00:00"));
    }

    @Test
    void statsReadDoesNotWriteAndUsesDefaultsBeforeFirstSimulation() {
        when(repository.findByAccountId(7L)).thenReturn(Mono.empty());

        StepVerifier.create(service.getStats(7L))
                .expectNext(new ao.creativemode.kixi.gamification.dto.UserStatsResponse(
                        7L, 0L, 1, 0, 0, 1, null))
                .verifyComplete();

        verify(repository, never()).ensureStats(7L);
    }

    private static UserStats stats(Long accountId, Long xp, Integer level, Integer streak,
            Integer freePauses, LocalDate lastActivity) {
        UserStats stats = new UserStats();
        stats.setAccountId(accountId);
        stats.setXp(xp);
        stats.setLevel(level);
        stats.setStreak(streak);
        stats.setLongestStreak(streak);
        stats.setFreePauses(freePauses);
        stats.setLastActivityDate(lastActivity);
        return stats;
    }
}
