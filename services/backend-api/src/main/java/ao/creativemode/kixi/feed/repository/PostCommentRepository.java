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

    /**
     * The moderation target (issue #313): a comment of this post, hidden or not.
     *
     * <p>{@code isHidden} is deliberately absent from the filter, because
     * unhiding is the one operation that can only find a hidden row, and hiding
     * has to be idempotent on an already-hidden one. Filtering on it here would
     * make {@code unhide} a 404 the second time a moderator runs it.</p>
     *
     * <p>{@code postId} is in the predicate, not only in the caller's head: a
     * comment id is guessable, and pairing it with the post the moderator was
     * allowed to act on is what stops an id from another school's thread from
     * being taken through the wrong post.</p>
     */
    Mono<PostComment> findByIdAndPostIdAndDeletedAtIsNull(Long id, Long postId);

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
