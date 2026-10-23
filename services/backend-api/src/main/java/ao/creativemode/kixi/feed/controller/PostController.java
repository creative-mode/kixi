package ao.creativemode.kixi.feed.controller;

import ao.creativemode.kixi.feed.dto.post.PostRequest;
import ao.creativemode.kixi.feed.dto.post.PostResponse;
import ao.creativemode.kixi.feed.dto.postComment.PostCommentRequest;
import ao.creativemode.kixi.feed.dto.postComment.PostCommentResponse;
import ao.creativemode.kixi.feed.dto.postModeration.ReportedPostResponse;
import ao.creativemode.kixi.feed.dto.postReaction.PostReactionRequest;
import ao.creativemode.kixi.feed.dto.postReaction.PostReactionSetRequest;
import ao.creativemode.kixi.feed.dto.postReaction.PostReactionToggleResponse;
import ao.creativemode.kixi.feed.dto.postReport.PostReportRequest;
import ao.creativemode.kixi.feed.dto.postReport.PostReportResponse;
import ao.creativemode.kixi.feed.service.PostService;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The accountId behind every call here comes from {@link CurrentAccountService},
 * never from {@code @AuthenticationPrincipal} read as a raw type: that is what
 * made the original rate-limiting filter crash with a {@code ClassCastException}
 * on every POST (issue #120, review point 7) - the principal is a {@code String}
 * everywhere else in this codebase, and this controller follows that.
 */
@RestController
@RequestMapping("/api/v1/feed/posts")
public class PostController {

    private final PostService postService;
    private final CurrentAccountService current;

    public PostController(PostService postService, CurrentAccountService current) {
        this.postService = postService;
        this.current = current;
    }

    /** Feed restricted to the caller's own school; optional params only narrow further inside it. */
    @GetMapping
    public Flux<PostResponse> getFeed(
            @RequestParam(required = false) Long schoolYearId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) Long classId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return current.requiredAccountId()
                .flatMapMany(accountId -> postService.getFeed(accountId, schoolYearId, courseId, classId, page, limit));
    }

    @GetMapping("/count")
    public Mono<Long> getFeedTotalCount(
            @RequestParam(required = false) Long schoolYearId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) Long classId
    ) {
        return current.requiredAccountId()
                .flatMap(accountId -> postService.getFeedTotalCount(accountId, schoolYearId, courseId, classId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PostResponse> createPost(@Valid @RequestBody PostRequest request) {
        return current.requiredAccountId()
                .flatMap(accountId -> postService.createPost(accountId, request));
    }

    @PostMapping("/{postId}/reactions")
    public Mono<PostReactionToggleResponse> toggleReaction(
            @PathVariable Long postId,
            @Valid @RequestBody PostReactionRequest request
    ) {
        return current.requiredAccountId()
                .flatMap(accountId -> postService.toggleReaction(postId, accountId, request));
    }

    @PutMapping("/{postId}/reactions")
    public Mono<PostReactionToggleResponse> setReaction(
            @PathVariable Long postId,
            @Valid @RequestBody PostReactionSetRequest request
    ) {
        return current.requiredAccountId()
                .flatMap(accountId -> postService.setReaction(postId, accountId, request));
    }

    @PostMapping("/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PostCommentResponse> addComment(
            @PathVariable Long postId,
            @Valid @RequestBody PostCommentRequest request
    ) {
        return current.requiredAccountId()
                .flatMap(accountId -> postService.addComment(postId, accountId, request));
    }

    /**
     * The conversation on a post, oldest first.
     *
     * <p>A post from outside the caller's school is 404 here exactly as it is on the post
     * itself: the id comes from the URL, and answering 403 would confirm it exists.</p>
     */
    @GetMapping("/{postId}/comments")
    public Flux<PostCommentResponse> listComments(
            @PathVariable Long postId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return current.requiredAccountId()
                .flatMapMany(accountId -> postService.listComments(postId, accountId, page, limit));
    }

    @PostMapping("/{postId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<PostReportResponse> reportPost(
            @PathVariable Long postId,
            @Valid @RequestBody PostReportRequest request
    ) {
        return current.requiredAccountId()
                .flatMap(accountId -> postService.reportPost(postId, accountId, request));
    }

    /** A TEACHER may hide only a post in a class they teach; an ADMIN, any post. The service enforces this, not the route. */
    @PatchMapping("/{postId}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> hidePost(@PathVariable Long postId) {
        return current.requiredAccountId().zipWith(current.hasAnyRole("ADMIN"))
                .flatMap(tuple -> postService.hidePost(postId, tuple.getT1(), tuple.getT2()));
    }

    @PatchMapping("/{postId}/unhide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> unhidePost(@PathVariable Long postId) {
        return current.requiredAccountId().zipWith(current.hasAnyRole("ADMIN"))
                .flatMap(tuple -> postService.unhidePost(postId, tuple.getT1(), tuple.getT2()));
    }

    /**
     * Hides a comment on a post (issue #313). A TEACHER may only hide a comment
     * under a post in a class they teach; an ADMIN, any. The post is named in
     * the URL because who may moderate a comment is decided by the post it
     * hangs from, and the service enforces it, not the route.
     */
    @PatchMapping("/{postId}/comments/{commentId}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> hideComment(@PathVariable Long postId, @PathVariable Long commentId) {
        return current.requiredAccountId().zipWith(current.hasAnyRole("ADMIN"))
                .flatMap(tuple -> postService.hideComment(postId, commentId, tuple.getT1(), tuple.getT2()));
    }

    /** The counterpart of {@link #hideComment}: a comment taken down can be shown again. */
    @PatchMapping("/{postId}/comments/{commentId}/unhide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> unhideComment(@PathVariable Long postId, @PathVariable Long commentId) {
        return current.requiredAccountId().zipWith(current.hasAnyRole("ADMIN"))
                .flatMap(tuple -> postService.unhideComment(postId, commentId, tuple.getT1(), tuple.getT2()));
    }

    /** The reported-post queue: a TEACHER's own classes, or every school for an ADMIN. */
    @GetMapping("/reported")
    public Flux<ReportedPostResponse> listReported() {
        return current.requiredAccountId().zipWith(current.hasAnyRole("ADMIN"))
                .flatMapMany(tuple -> postService.listReported(tuple.getT1(), tuple.getT2()));
    }
}
