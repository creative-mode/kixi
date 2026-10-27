package ao.creativemode.kixi.moderation.repository;

import ao.creativemode.kixi.moderation.model.ModerationBlock;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface ModerationBlockRepository extends ReactiveCrudRepository<ModerationBlock, Long> {
    @Query("SELECT EXISTS (SELECT 1 FROM moderation_blocks WHERE account_low = :low AND account_high = :high)")
    Mono<Boolean> existsPair(Long low, Long high);

    @Query("DELETE FROM moderation_blocks WHERE account_low = :low AND account_high = :high")
    Mono<Integer> deletePair(Long low, Long high);

    /** Atomically toggles a pair while serializing concurrent requests for that pair. */
    @Query("""
            WITH lock AS (
                SELECT pg_advisory_xact_lock(hashtextextended(CONCAT(:low, ':', :high), 0))
            ), deleted AS (
                DELETE FROM moderation_blocks
                WHERE account_low = :low AND account_high = :high
                  AND EXISTS (SELECT 1 FROM lock)
                RETURNING id
            )
            INSERT INTO moderation_blocks (account_low, account_high, created_at)
            SELECT :low, :high, CURRENT_TIMESTAMP
            WHERE NOT EXISTS (SELECT 1 FROM deleted)
            RETURNING TRUE AS blocked
            """)
    Mono<Boolean> togglePair(Long low, Long high);
}
