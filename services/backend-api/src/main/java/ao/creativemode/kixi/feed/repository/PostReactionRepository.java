package ao.creativemode.kixi.feed.repository;

import ao.creativemode.kixi.feed.model.PostReaction;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface PostReactionRepository extends ReactiveCrudRepository<PostReaction, Long> {

    Mono<PostReaction> findByPostIdAndAccountIdAndType(Long postId, Long accountId, String type);

    Mono<Void> deleteByPostIdAndAccountIdAndType(Long postId, Long accountId, String type);

    Mono<Boolean> existsByPostIdAndAccountId(Long postId, Long accountId);

    @Query("SELECT type, COUNT(id) AS count FROM post_reactions WHERE post_id = :postId GROUP BY type")
    Flux<ReactionCountProjection> countReactionsByPostId(Long postId);

    interface ReactionCountProjection {
        String getType();
        Long getCount();
    }
}