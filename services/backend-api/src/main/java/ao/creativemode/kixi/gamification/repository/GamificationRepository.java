package ao.creativemode.kixi.gamification.repository;

import ao.creativemode.kixi.gamification.model.UserStats;
import java.time.LocalDateTime;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface GamificationRepository extends ReactiveCrudRepository<UserStats, Long> {
    @Modifying
    @Query("INSERT INTO user_stats (account_id) VALUES (:accountId) ON CONFLICT (account_id) DO NOTHING")
    Mono<Integer> ensureStats(Long accountId);

    @Query("SELECT * FROM user_stats WHERE account_id = :accountId FOR UPDATE")
    Mono<UserStats> lockByAccountId(Long accountId);

    Mono<UserStats> findByAccountId(Long accountId);

    @Modifying
    @Query("INSERT INTO simulation_xp_awards (simulation_id, account_id, subject_id, score_percent, xp_awarded, awarded_at) "
        + "VALUES (:simulationId, :accountId, :subjectId, :scorePercent, :xpAwarded, :awardedAt) "
        + "ON CONFLICT (simulation_id) DO UPDATE SET account_id = EXCLUDED.account_id, "
        + "subject_id = EXCLUDED.subject_id, score_percent = EXCLUDED.score_percent, xp_awarded = EXCLUDED.xp_awarded")
    Mono<Integer> saveSimulationAward(Long simulationId, Long accountId, Long subjectId,
            Double scorePercent, Integer xpAwarded, LocalDateTime awardedAt);

    @Query("SELECT xp_awarded FROM simulation_xp_awards WHERE simulation_id = :simulationId")
    Mono<Integer> findSimulationAward(Long simulationId);

    @Query("SELECT score_percent FROM simulation_xp_awards WHERE account_id = :accountId "
        + "AND subject_id = :subjectId ORDER BY awarded_at DESC, simulation_id DESC LIMIT 3")
    Flux<Double> findLatestTopicScores(Long accountId, Long subjectId);

    @Modifying
    @Query("INSERT INTO user_badges (account_id, badge_id, subject_id, unlocked_at) "
        + "SELECT :accountId, id, :subjectId, :unlockedAt FROM badges WHERE code = :code "
        + "ON CONFLICT DO NOTHING")
    Mono<Integer> unlockBadge(Long accountId, String code, Long subjectId, LocalDateTime unlockedAt);
}
