package ao.creativemode.kixi.feed.repository;

import ao.creativemode.kixi.feed.model.Post;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Plain CRUD on a post by its own id. The scope-aware listing and counting
 * that the feed and its authorization depend on live in {@link PostFeedRepository}
 * instead, because {@code ANY(:ids)} over a caller's authorized classes/courses/
 * school years needs array binding that a derived query cannot express.
 */
@Repository
public interface PostRepository extends ReactiveCrudRepository<Post, Long> {

    Mono<Post> findByIdAndIsHiddenFalseAndDeletedAtIsNull(Long id);

    Mono<Post> findByIdAndDeletedAtIsNull(Long id);
}
