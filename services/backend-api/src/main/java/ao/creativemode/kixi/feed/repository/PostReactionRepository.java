package ao.creativemode.kixi.feed.repository;

import ao.creativemode.kixi.feed.model.PostReaction;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface PostReactionRepository extends ReactiveCrudRepository<PostReaction, Long> {

    Mono<Boolean> existsByPostIdAndAccountId(Long postId, Long accountId);

    /**
     * {@code deleteBy...} on a derived query returns {@code Mono<Void>}, which
     * completes empty whether or not a row existed - the exact bug the review
     * flagged (#175 round, issue #120 point 8): {@code switchIfEmpty} after it
     * always ran. {@code @Modifying} on an explicit statement answers the
     * number of rows removed instead, so the caller can tell "removed" apart
     * from "there was nothing to remove".
     */
    @Modifying
    @Query("DELETE FROM post_reactions WHERE post_id = :postId AND account_id = :accountId AND type = :type")
    Mono<Integer> deleteReaction(Long postId, Long accountId, String type);

    /**
     * {@code ON CONFLICT DO NOTHING} so a race with the delete above (or with
     * itself, two taps arriving together) cannot violate the unique constraint
     * and surface as a 500; it simply inserts nothing the second time.
     */
    @Modifying
    @Query("""
            INSERT INTO post_reactions (post_id, account_id, type, created_at)
            VALUES (:postId, :accountId, :type, CURRENT_TIMESTAMP)
            ON CONFLICT (post_id, account_id, type) DO NOTHING
            """)
    Mono<Integer> insertReaction(Long postId, Long accountId, String type);

    @Query("SELECT type, COUNT(id) AS count FROM post_reactions WHERE post_id = :postId GROUP BY type")
    Flux<ReactionCountProjection> countReactionsByPostId(Long postId);

    interface ReactionCountProjection {
        String getType();
        Long getCount();
    }
}
