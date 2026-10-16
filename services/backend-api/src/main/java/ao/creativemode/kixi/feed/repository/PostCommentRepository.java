package ao.creativemode.kixi.feed.repository;

import ao.creativemode.kixi.feed.model.PostComment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface PostCommentRepository extends ReactiveCrudRepository<PostComment, Long> {

    Mono<PostComment> findByIdAndIsHiddenFalseAndDeletedAtIsNull(Long id);

    Flux<PostComment> findByPostIdAndIsHiddenFalseAndDeletedAtIsNullOrderByCreatedAtAsc(Long postId);

    /**
     * The same conversation, one page of it.
     *
     * <p>Chronological and paged rather than paged-then-reversed: a page of the newest
     * comments would show the end of a conversation without the beginning, and the issue
     * is that a student cannot read what came before what they are about to answer.</p>
     */
    Flux<PostComment> findByPostIdAndIsHiddenFalseAndDeletedAtIsNullOrderByCreatedAtAsc(
            Long postId, Pageable pageable);

    Mono<Long> countByPostIdAndIsHiddenFalseAndDeletedAtIsNull(Long postId);
}
