package ao.creativemode.kixi.feed.repository;

import ao.creativemode.kixi.feed.model.PostComment;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface PostCommentRepository extends ReactiveCrudRepository<PostComment, Long> {

    Mono<PostComment> findByIdAndIsHiddenFalseAndDeletedAtIsNull(Long id);

    Flux<PostComment> findByPostIdAndIsHiddenFalseAndDeletedAtIsNullOrderByCreatedAtAsc(Long postId);

    Mono<Long> countByPostIdAndIsHiddenFalseAndDeletedAtIsNull(Long postId);
}