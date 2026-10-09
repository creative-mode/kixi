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

    @GetMapping
    public Flux<PostResponse> getFeed(
            @RequestHeader("X-Account-Id") Long accountId, // Substitua pelo seu @AuthenticationPrincipal se usar Spring Security / JWT padrão
            @RequestParam(required = false) Long schoolYearId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) Long classId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return postService.getFeed(accountId, schoolYearId, courseId, classId, page, limit);
    }

    @GetMapping("/count")
    public Mono<Long> getFeedTotalCount(
            @RequestParam(required = false) Long schoolYearId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) Long classId
    ) {
        return postService.getFeedTotalCount(schoolYearId, courseId, classId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PostResponse> createPost(
            @RequestHeader("X-Account-Id") Long accountId,
            @RequestParam(required = false) Long schoolYearId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) Long classId,
            @Valid @RequestBody PostRequest request
    ) {
        return postService.createPost(accountId, schoolYearId, courseId, classId, request);
    }

    @PostMapping("/{postId}/reactions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> toggleReaction(
            @PathVariable Long postId,
            @RequestHeader("X-Account-Id") Long accountId,
            @Valid @RequestBody PostReactionRequest request
    ) {
        return postService.toggleReaction(postId, accountId, request);
    }

    @PostMapping("/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PostCommentResponse> addComment(
            @PathVariable Long postId,
            @RequestHeader("X-Account-Id") Long accountId,
            @Valid @RequestBody PostCommentRequest request
    ) {
        return postService.addComment(postId, accountId, request);
    }

    @PostMapping("/{postId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PostReportResponse> reportPost(
            @PathVariable Long postId,
            @RequestHeader("X-Account-Id") Long accountId,
            @Valid @RequestBody PostReportRequest request
    ) {
        return postService.reportPost(postId, accountId, request);
    }
}