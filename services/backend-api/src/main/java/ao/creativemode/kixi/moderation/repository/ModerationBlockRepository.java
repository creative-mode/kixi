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

    /**
     * Atomically toggles a pair while serializing concurrent requests for that pair.
     *
     * The {@code lock} CTE has to be referenced more than once (here, in both the
     * DELETE's WHERE and the INSERT's FROM) so Postgres materializes it instead of
     * inlining it: materialized, it runs exactly once, unconditionally, before either
     * branch decides anything. A single reference inside the DELETE's WHERE alone is
     * not enough — when no row matches the pair yet (the very race this exists to
     * serialize: two requests blocking the same pair for the first time), Postgres
     * never evaluates that per-row predicate, so the lock would silently never be taken.
     */
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
            SELECT :low, :high, CURRENT_TIMESTAMP FROM lock
            WHERE NOT EXISTS (SELECT 1 FROM deleted)
            RETURNING TRUE AS blocked
            """)
    Mono<Boolean> togglePair(Long low, Long high);
}
