package ao.creativemode.kixi.gamification.service;

import ao.creativemode.kixi.gamification.dto.UserBadgeResponse;
import ao.creativemode.kixi.gamification.dto.UserStatsResponse;
import ao.creativemode.kixi.gamification.model.Badge;
import ao.creativemode.kixi.gamification.model.UserBadge;
import ao.creativemode.kixi.gamification.model.UserStats;
import ao.creativemode.kixi.gamification.repository.BadgeRepository;
import ao.creativemode.kixi.gamification.repository.GamificationRepository;
import ao.creativemode.kixi.gamification.repository.UserBadgeRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Applies server-calculated study rewards and exposes the signed-in user's progress. */
@Service
public class GamificationService {
    private static final String FIRST_SIMULATION = "FIRST_SIMULATION";
    private static final String STREAK_30_DAYS = "STREAK_30_DAYS";
    private static final String TOPIC_MASTERED = "TOPIC_MASTERED";

    private final GamificationRepository stats;
    private final BadgeRepository badges;
    private final UserBadgeRepository userBadges;
    private final Clock clock;

    public GamificationService(GamificationRepository stats, BadgeRepository badges,
            UserBadgeRepository userBadges, Clock clock) {
        this.stats = stats;
        this.badges = badges;
        this.userBadges = userBadges;
        this.clock = clock;
    }

    /**
     * Grants or reconciles rewards for a finished simulation. The unique award row
     * makes retries safe; a later teacher grade adjusts XP to the final score without
     * counting the same study day twice.
     */
    @Transactional
    public Mono<Void> simulationFinished(Long simulationId, Long accountId, Long subjectId,
            double score, double maximumScore, Integer timeSpentSeconds, LocalDateTime finishedAt,
            boolean countStudyDay) {
        double percentage = normalizedScore(score, maximumScore);
        int xpAwarded = xpFor(percentage, timeSpentSeconds);
        LocalDate activityDate = finishedAt == null ? LocalDate.now(clock) : finishedAt.toLocalDate();
        LocalDateTime now = LocalDateTime.now(clock);

        return stats.ensureStats(accountId)
            .then(stats.lockByAccountId(accountId)
                .switchIfEmpty(Mono.error(new IllegalStateException("Gamification stats row was not created"))))
            .flatMap(userStats -> stats.findSimulationAward(simulationId)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(previousAward -> stats.saveSimulationAward(simulationId, accountId, subjectId,
                        percentage, xpAwarded, now)
                    .then(Mono.defer(() -> {
                        long previousXp = previousAward.orElse(0);
                        userStats.setXp(Math.max(0L, safeLong(userStats.getXp()) + xpAwarded - previousXp));
                        userStats.setLevel(levelFor(userStats.getXp()));
                        if (countStudyDay && previousAward.isEmpty()) {
                            updateStreak(userStats, activityDate);
                        }
                        userStats.setUpdatedAt(now);
                        return stats.save(userStats).then(unlockAchievements(userStats, accountId,
                                previousAward.isEmpty(), subjectId, now));
                    }))));
    }

    public Mono<UserStatsResponse> getStats(Long accountId) {
        return stats.findByAccountId(accountId)
            .map(GamificationService::toResponse)
            .defaultIfEmpty(new UserStatsResponse(accountId, 0L, 1, 0, 0, 1, null));
    }

    public Flux<UserBadgeResponse> getBadges(Long accountId) {
        return userBadges.findAllByAccountIdOrderByUnlockedAtDesc(accountId)
            .concatMap(this::toResponse);
    }

    private Mono<Void> unlockAchievements(UserStats userStats, Long accountId, boolean firstAward,
            Long subjectId, LocalDateTime now) {
        Mono<Void> first = firstAward
            ? unlock(accountId, FIRST_SIMULATION, null, now)
            : Mono.empty();
        Mono<Void> streak = userStats.getStreak() >= 30
            ? unlock(accountId, STREAK_30_DAYS, null, now)
            : Mono.empty();
        Mono<Void> topic = subjectId == null
            ? Mono.empty()
            : stats.findLatestTopicScores(accountId, subjectId).take(3).collectList()
                .filter(scores -> scores.size() == 3
                    && scores.stream().mapToDouble(Double::doubleValue).average().orElse(0) >= 80.0)
                .flatMap(ignored -> unlock(accountId, TOPIC_MASTERED, subjectId, now));
        return first.then(streak).then(topic);
    }

    private Mono<Void> unlock(Long accountId, String code, Long subjectId, LocalDateTime now) {
        return stats.unlockBadge(accountId, code, subjectId, now).then();
    }

    private void updateStreak(UserStats userStats, LocalDate activityDate) {
        LocalDate lastActivity = userStats.getLastActivityDate();
        if (lastActivity == null) {
            userStats.setStreak(1);
            userStats.setLastActivityDate(activityDate);
        } else {
            long daysSinceActivity = ChronoUnit.DAYS.between(lastActivity, activityDate);
            if (daysSinceActivity == 1) {
                userStats.setStreak(safeInt(userStats.getStreak()) + 1);
                userStats.setLastActivityDate(activityDate);
            } else if (daysSinceActivity == 2 && safeInt(userStats.getFreePauses()) > 0) {
                // The pause covers the single missed calendar day; the active day also counts.
                userStats.setStreak(safeInt(userStats.getStreak()) + 2);
                userStats.setFreePauses(userStats.getFreePauses() - 1);
                userStats.setLastActivityDate(activityDate);
            } else if (daysSinceActivity > 1) {
                userStats.setStreak(1);
                userStats.setLastActivityDate(activityDate);
            }
        }
        userStats.setLongestStreak(Math.max(safeInt(userStats.getLongestStreak()), safeInt(userStats.getStreak())));
    }

    private Mono<UserBadgeResponse> toResponse(UserBadge userBadge) {
        return badges.findById(userBadge.getBadgeId())
            .map(badge -> toResponse(badge, userBadge));
    }

    private static UserBadgeResponse toResponse(Badge badge, UserBadge userBadge) {
        return new UserBadgeResponse(badge.getCode(), badge.getName(), badge.getDescription(),
                userBadge.getSubjectId(), userBadge.getUnlockedAt());
    }

    private static UserStatsResponse toResponse(UserStats value) {
        return new UserStatsResponse(value.getAccountId(), value.getXp(), value.getLevel(), value.getStreak(),
                value.getLongestStreak(), value.getFreePauses(), value.getLastActivityDate());
    }

    private static double normalizedScore(double score, double maximumScore) {
        if (maximumScore <= 0) return 0;
        return Math.max(0, Math.min(100, score / maximumScore * 100));
    }

    private static int xpFor(double scorePercent, Integer timeSpentSeconds) {
        int performanceXp = (int) Math.round(scorePercent);
        int effortXp = Math.min(20, Math.max(0, safeInt(timeSpentSeconds)) / 60);
        return Math.max(1, performanceXp + effortXp);
    }

    /** Level thresholds grow gradually: 100 XP for level 2, then +200, +300, and so on. */
    private static int levelFor(long xp) {
        return (int) Math.floor((1 + Math.sqrt(1 + (8.0 * Math.max(0, xp) / 100.0))) / 2);
    }

    private static long safeLong(Long value) { return value == null ? 0L : value; }
    private static int safeInt(Integer value) { return value == null ? 0 : value; }
}
