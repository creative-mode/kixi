package ao.creativemode.kixi.feed.controller;

import ao.creativemode.kixi.feed.dto.postComment.PostCommentRequest;
import ao.creativemode.kixi.feed.dto.postComment.PostCommentResponse;
import ao.creativemode.kixi.feed.dto.post.PostRequest;
import ao.creativemode.kixi.feed.dto.post.PostResponse;
import ao.creativemode.kixi.feed.dto.postReaction.PostReactionRequest;
import ao.creativemode.kixi.feed.dto.postReport.PostReportRequest;
import ao.creativemode.kixi.feed.dto.postReport.PostReportResponse;
import ao.creativemode.kixi.feed.service.PostService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/feed/posts")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    /**
     * Retrieve feed posts filtered by optional academic scope and paginated.
     */
    @GetMapping
    public Flux<PostResponse> getFeed(
            @AuthenticationPrincipal String principalId,
            @RequestParam(required = false) Long schoolYearId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) Long classId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit
    ) {
        Long accountId = principalId != null ? Long.valueOf(principalId) : null;
        return postService.getFeed(accountId, schoolYearId, courseId, classId, page, limit);
    }

    /**
     * Get total count of feed posts for pagination metadata.
     */
    @GetMapping("/count")
    public Mono<Long> getFeedTotalCount(
            @RequestParam(required = false) Long schoolYearId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) Long classId
    ) {
        return postService.getFeedTotalCount(schoolYearId, courseId, classId);
    }

    /**
     * Create a new manual post in the feed.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PostResponse> createPost(
            @AuthenticationPrincipal String principalId,
            @RequestParam(required = false) Long schoolYearId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) Long classId,
            @Valid @RequestBody PostRequest request
    ) {
        Long accountId = Long.valueOf(principalId);
        return postService.createPost(accountId, schoolYearId, courseId, classId, request);
    }

    /**
     * Toggle a reaction on a specific post.
     */
    @PostMapping("/{postId}/reactions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> toggleReaction(
            @PathVariable Long postId,
            @AuthenticationPrincipal String principalId,
            @Valid @RequestBody PostReactionRequest request
    ) {
        Long accountId = Long.valueOf(principalId);
        return postService.toggleReaction(postId, accountId, request);
    }

    /**
     * Add a comment to a post.
     */
    @PostMapping("/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PostCommentResponse> addComment(
            @PathVariable Long postId,
            @AuthenticationPrincipal String principalId,
            @Valid @RequestBody PostCommentRequest request
    ) {
        Long accountId = Long.valueOf(principalId);
        return postService.addComment(postId, accountId, request);
    }

    /**
     * Report an inappropriate post.
     */
    @PostMapping("/{postId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PostReportResponse> reportPost(
            @PathVariable Long postId,
            @AuthenticationPrincipal String principalId,
            @Valid @RequestBody PostReportRequest request
    ) {
        Long accountId = Long.valueOf(principalId);
        return postService.reportPost(postId, accountId, request);
    }

    /**
     * Hide a post manually. Restricted to teachers and administrators.
     */
    @PatchMapping("/{postId}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public Mono<Void> hidePost(@PathVariable Long postId) {
        return postService.hidePost(postId);
    }
}