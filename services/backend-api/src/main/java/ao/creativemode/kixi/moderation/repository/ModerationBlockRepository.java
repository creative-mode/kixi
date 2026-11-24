package ao.creativemode.kixi.moderation.repository;

import ao.creativemode.kixi.moderation.model.ModerationBlock;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ModerationBlockRepository extends ReactiveCrudRepository<ModerationBlock, Long> {
    @Query("SELECT EXISTS (SELECT 1 FROM moderation_blocks WHERE account_low = :low AND account_high = :high)")
    Mono<Boolean> existsPair(Long low, Long high);

    @Modifying
    @Query("DELETE FROM moderation_blocks WHERE account_low = :low AND account_high = :high")
    Mono<Integer> deletePair(Long low, Long high);

    Mono<ModerationBlock> findByAccountLowAndAccountHigh(Long low, Long high);

    /** Puts the block if the pair has none yet; a pair that is already blocked keeps its original blocker. */
    @Modifying
    @Query("""
            INSERT INTO moderation_blocks (account_low, account_high, blocked_by, created_at)
            VALUES (:low, :high, :blockedBy, CURRENT_TIMESTAMP)
            ON CONFLICT (account_low, account_high) DO NOTHING
            """)
    Mono<Integer> insertPair(Long low, Long high, Long blockedBy);

    /** What the account blocked, newest first (rows of unknown blocker count for both sides). */
    @Query("""
            SELECT * FROM moderation_blocks
             WHERE blocked_by = :accountId
                OR (blocked_by IS NULL AND (account_low = :accountId OR account_high = :accountId))
             ORDER BY created_at DESC, id DESC
            """)
    Flux<ModerationBlock> findBlockedBy(Long accountId);
}
