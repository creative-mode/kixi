package ao.creativemode.kixi.feed.repository;

import ao.creativemode.kixi.feed.model.Post;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface PostRepository extends ReactiveCrudRepository<Post, Long> {

    Mono<Post> findByIdAndIsHiddenFalseAndDeletedAtIsNull(Long id);

    @Query("""
        SELECT p.* FROM posts p
        WHERE p.is_hidden = false 
          AND p.deleted_at IS NULL
          AND (
            p.class_id = :classId 
            OR p.course_id = :courseId 
            OR p.school_year_id = :schoolYearId
            OR (p.class_id IS NULL AND p.course_id IS NULL AND p.school_year_id IS NULL)
          )
        ORDER BY p.created_at DESC
        OFFSET :offset LIMIT :limit
    """)
    Flux<Post> findFeedPosts(Long schoolYearId, Long courseId, Long classId, long offset, int limit);

    @Query("""
        SELECT COUNT(p.id) FROM posts p
        WHERE p.is_hidden = false 
          AND p.deleted_at IS NULL
          AND (
            p.class_id = :classId 
            OR p.course_id = :courseId 
            OR p.school_year_id = :schoolYearId
            OR (p.class_id IS NULL AND p.course_id IS NULL AND p.school_year_id IS NULL)
          )
    """)
    Mono<Long> countFeedPosts(Long schoolYearId, Long courseId, Long classId);
}