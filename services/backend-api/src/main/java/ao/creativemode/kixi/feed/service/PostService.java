package ao.creativemode.kixi.feed.service;

import ao.creativemode.kixi.feed.dto.post.PostRequest;
import ao.creativemode.kixi.feed.dto.post.PostResponse;
import ao.creativemode.kixi.feed.dto.postComment.PostCommentRequest;
import ao.creativemode.kixi.feed.dto.postComment.PostCommentResponse;
import ao.creativemode.kixi.feed.dto.postModeration.ReportedPostResponse;
import ao.creativemode.kixi.feed.dto.postReaction.PostReactionRequest;
import ao.creativemode.kixi.feed.dto.postReaction.PostReactionToggleResponse;
import ao.creativemode.kixi.feed.dto.postReport.PostReportRequest;
import ao.creativemode.kixi.feed.dto.postReport.PostReportResponse;
import ao.creativemode.kixi.feed.enums.PostType;
import ao.creativemode.kixi.feed.model.Post;
import ao.creativemode.kixi.feed.model.PostComment;
import ao.creativemode.kixi.feed.model.PostReport;
import ao.creativemode.kixi.feed.repository.PostCommentRepository;
import ao.creativemode.kixi.feed.repository.PostFeedRepository;
import ao.creativemode.kixi.feed.repository.PostReactionRepository;
import ao.creativemode.kixi.feed.repository.PostReportRepository;
import ao.creativemode.kixi.feed.repository.PostRepository;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.service.DisplayNameReader;
import ao.creativemode.kixi.shared.service.FeedScopeAuthorizer;
import ao.creativemode.kixi.shared.service.FeedScopeAuthorizer.ClassScope;
import ao.creativemode.kixi.shared.service.FeedScopeAuthorizer.FeedScope;
import ao.creativemode.kixi.shared.service.InstitutionNameReader;
import ao.creativemode.kixi.shared.service.SimulationShowcaseReader;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The social feed of issue #120: students and teachers raise doubts and
 * topics inside their own school, and react to and comment on each other's.
 *
 * <p>Isolation is the point of this class, not an afterthought: every read
 * and every write is scoped through {@link FeedScopeAuthorizer}, which is the
 * server's own view of which classes, courses, school years and - above all -
 * which institution an account actually belongs to. Nothing here trusts a
 * scope id the client supplies; a client-supplied class/course/year is, at
 * most, a narrowing of what the server already allowed.</p>
 */
@Service
public class PostService {

    private static final int AUTO_HIDE_REPORT_THRESHOLD = 5;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 50;

    private final PostRepository postRepository;
    private final PostFeedRepository feedRepository;
    private final PostReactionRepository reactionRepository;
    private final PostCommentRepository commentRepository;
    private final DisplayNameReader displayNames;
    private final PostReportRepository reportRepository;
    private final FeedScopeAuthorizer scopeAuthorizer;
    private final FeedRateLimiter rateLimiter;
    private final SimulationShowcaseReader showcaseReader;
    private final InstitutionNameReader institutionNames;
    private final Clock clock;

    public PostService(PostRepository postRepository,
                       PostFeedRepository feedRepository,
                       PostReactionRepository reactionRepository,
                       PostCommentRepository commentRepository,
                       DisplayNameReader displayNames,
                       PostReportRepository reportRepository,
                       FeedScopeAuthorizer scopeAuthorizer,
                       FeedRateLimiter rateLimiter,
                       SimulationShowcaseReader showcaseReader,
                       InstitutionNameReader institutionNames,
                       Clock clock) {
        this.postRepository = postRepository;
        this.feedRepository = feedRepository;
        this.reactionRepository = reactionRepository;
        this.commentRepository = commentRepository;
        this.displayNames = displayNames;
        this.reportRepository = reportRepository;
        this.scopeAuthorizer = scopeAuthorizer;
        this.rateLimiter = rateLimiter;
        this.showcaseReader = showcaseReader;
        this.institutionNames = institutionNames;
        this.clock = clock;
    }

    /**
     * The feed, restricted on the server to the caller's own school. An id the
     * caller passes to narrow it (one class, one course, one school year) is
     * accepted only when it is already inside their scope - a forged one is
     * refused, not silently ignored.
     */
    public Flux<PostResponse> getFeed(
            Long accountId, Long schoolYearId, Long courseId, Long classId, int page, int limit) {
        return Flux.defer(() -> {
            validatePagination(page, limit);
            return scopeAuthorizer.scopeOf(accountId)
                    .flatMapMany(scope -> {
                        requireWithinScope(scope, schoolYearId, courseId, classId);
                        long offset = (long) (page - 1) * limit;
                        return feedRepository.findFeed(scope, schoolYearId, courseId, classId, limit, offset)
                                .flatMapSequential(post -> mapToResponse(post, accountId));
                    });
        });
    }

    /** Total count of posts for the given scope, with the same server-side isolation as {@link #getFeed}. */
    public Mono<Long> getFeedTotalCount(Long accountId, Long schoolYearId, Long courseId, Long classId) {
        return scopeAuthorizer.scopeOf(accountId)
                .flatMap(scope -> {
                    requireWithinScope(scope, schoolYearId, courseId, classId);
                    return feedRepository.countFeed(scope, schoolYearId, courseId, classId);
                });
    }

    /**
     * A new manual post. The scope is derived entirely from {@code request.classId()}:
     * the server looks up whether the caller actually belongs to that class and, if so,
     * fills in the course, school year and institution from the class itself. There is
     * no "global" post any more - every post sits inside exactly one school.
     */
    @Transactional
    public Mono<PostResponse> createPost(Long accountId, PostRequest request) {
        if (!request.type().isManual()) {
            return Mono.error(ApiException.badRequest(
                    "Only DOUBT, TOPIC, SIMULATION, STUDY_CARD, SOLUTION or CHALLENGE posts can be created manually"));
        }
        boolean simulation = request.type() == PostType.SIMULATION;
        final java.util.List<ao.creativemode.kixi.feed.dto.post.PostBlock> blocks;
        try {
            blocks = PostBlocks.validate(request.blocks());
        } catch (ApiException invalid) {
            return Mono.error(invalid);
        }
        if (simulation && !blocks.isEmpty()) {
            return Mono.error(ApiException.badRequest("A SIMULATION post has no blocks"));
        }
        if (request.type().usesBlocks() && blocks.isEmpty()) {
            return Mono.error(ApiException.badRequest("A " + request.type().name() + " post needs blocks"));
        }
        if (request.type() == PostType.CHALLENGE && blocks.stream().noneMatch(b -> b.type().equals("choice"))) {
            return Mono.error(ApiException.badRequest("A CHALLENGE post needs a choice block"));
        }
        String content = blocks.isEmpty()
                ? (request.content() == null ? "" : request.content().trim())
                : PostBlocks.summary(blocks);
        if (simulation ? request.simulationId() == null : content.length() < 5) {
            return Mono.error(ApiException.badRequest(simulation
                    ? "simulationId is required for a SIMULATION post"
                    : "Content must have at least 5 characters"));
        }
        if (!simulation && request.simulationId() != null) {
            return Mono.error(ApiException.badRequest("simulationId only applies to a SIMULATION post"));
        }
        return rateLimiter.check(accountId)
                .then(Mono.defer(() -> requireClassScope(accountId, request.classId())))
                .flatMap(classScope -> simulation
                        ? attachShowcase(accountId, request, classScope)
                        : Mono.just(new Post()).map(post -> new Staged(classScope, post)))
                .flatMap(staged -> {
                    ClassScope classScope = staged.classScope();
                    Post post = staged.post();
                    post.setAuthorAccountId(accountId);
                    post.setInstitutionId(classScope.institutionId());
                    post.setSchoolYearId(classScope.schoolYearId());
                    post.setCourseId(classScope.courseId());
                    post.setClassId(classScope.classId());
                    post.setType(request.type().getValue());
                    post.setTitle(request.title() != null ? request.title().trim() : null);
                    post.setContent(content);
                    post.setBlocks(PostBlocks.toJson(blocks));
                    post.setIsAutoGenerated(false);
                    post.setIsHidden(false);
                    LocalDateTime now = LocalDateTime.now(clock);
                    post.setCreatedAt(now);
                    post.setUpdatedAt(now);
                    return postRepository.save(post);
                })
                .flatMap(saved -> mapToResponse(saved, accountId))
                .onErrorMap(DataIntegrityViolationException.class, e -> simulation
                        ? ApiException.conflict("This simulation was already published")
                        : ApiException.badRequest("Invalid post request payload"));
    }

    private record Staged(ClassScope classScope, Post post) { }

    /** The figures and the school's name are read here, never from the request. */
    private Mono<Staged> attachShowcase(Long accountId, PostRequest request, ClassScope classScope) {
        return showcaseReader.showcaseOf(request.simulationId(), accountId).flatMap(showcase ->
                institutionNames.nameOf(classScope.institutionId()).defaultIfEmpty("").map(schoolName -> {
                    Post post = new Post();
                    post.setSimulationId(showcase.simulationId());
                    post.setStatementTitle(showcase.statementTitle());
                    post.setSchoolName(schoolName.isBlank() ? null : schoolName);
                    post.setFinalScore(showcase.finalScore());
                    post.setMaxScore(showcase.maxScore());
                    post.setCorrectAnswers(showcase.correctAnswers());
                    post.setTotalQuestions(showcase.totalQuestions());
                    post.setTimeSpentSeconds(showcase.timeSpentSeconds());
                    return new Staged(classScope, post);
                }));
    }

    /**
     * Adds or removes the caller's reaction. {@code deleteReaction} answers the
     * number of rows it actually removed, so "there was one and it is gone" and
     * "there wasn't one" are never confused the way an empty {@code Mono<Void>}
     * confused them before, and {@code insertReaction}'s {@code ON CONFLICT DO
     * NOTHING} keeps a second, concurrent tap from ever hitting the unique
     * constraint as a 500.
     */
    @Transactional
    public Mono<PostReactionToggleResponse> toggleReaction(Long postId, Long accountId, PostReactionRequest request) {
        String type = request.reaction().getValue();
        return rateLimiter.check(accountId)
                .then(scopeAuthorizer.scopeOf(accountId))
                .flatMap(scope -> requireVisible(postId, scope))
                .flatMap(post -> reactionRepository.deleteReaction(postId, accountId, type)
                        .flatMap(deleted -> deleted > 0
                                ? Mono.just(new PostReactionToggleResponse(false))
                                : reactionRepository.insertReaction(postId, accountId, type)
                                        .map(inserted -> new PostReactionToggleResponse(inserted > 0))));
    }

    @Transactional
    public Mono<PostCommentResponse> addComment(Long postId, Long accountId, PostCommentRequest request) {
        return rateLimiter.check(accountId)
                .then(scopeAuthorizer.scopeOf(accountId))
                .flatMap(scope -> requireVisible(postId, scope))
                .flatMap(post -> {
                    PostComment comment = new PostComment();
                    comment.setPostId(postId);
                    comment.setAuthorAccountId(accountId);
                    comment.setContent(request.content().trim());
                    comment.setIsHidden(false);
                    LocalDateTime now = LocalDateTime.now(clock);
                    comment.setCreatedAt(now);
                    comment.setUpdatedAt(now);
                    return commentRepository.save(comment);
                })
                .flatMap(saved -> toCommentResponse(saved, accountId));
    }

    /**
     * The conversation on a post, oldest first.
     *
     * <p>The scope check is the whole point of this method: the id arrives in the URL and
     * a student who guesses one from another school must not read what was written under
     * it. {@link #requireVisible} answers 404 for that, the same way it does for the post
     * itself — not 403, which would confirm that the post exists.</p>
     *
     * <p>No rate limiter here, unlike {@link #addComment}: that one stops a student from
     * writing a hundred comments, and a read writes nothing.</p>
     */
    public Flux<PostCommentResponse> listComments(
            Long postId, Long accountId, int page, int limit) {
        return Flux.defer(() -> {
            validatePagination(page, limit);
            return scopeAuthorizer.scopeOf(accountId)
                    .flatMap(scope -> requireVisible(postId, scope))
                    .flatMapMany(visible -> commentRepository
                            .findByPostIdAndIsHiddenFalseAndDeletedAtIsNullOrderByCreatedAtAsc(
                                    postId, PageRequest.of(page - 1, limit))
                            .collectList()
                            .flatMapMany(comments -> {
                                // One read for the distinct authors, not one per comment.
                                List<Long> authors = comments.stream()
                                        .map(PostComment::getAuthorAccountId).distinct().toList();
                                return displayNames.briefsOf(authors)
                                        .defaultIfEmpty(Map.of())
                                        .flatMapMany(briefs -> Flux.fromIterable(comments)
                                                .map(comment -> toCommentResponse(
                                                        comment, accountId, briefs.get(comment.getAuthorAccountId()))));
                            }));
        });
    }

    /**
     * One comment, with a name the reader can use.
     *
     * <p>The label is masked the way the ranking masks it — first name and the initial of
     * the last — because a class feed shows several people and "um colega da turma" for
     * all of them answers nothing about who is talking. The account id stays on the row
     * for the client to recognise its own comments; it is the same id the ranking already
     * hands out and carries no more.</p>
     *
     * <p>A missing profile gives a null name and <em>still the comment</em>: the door
     * returns an empty Mono when there is no person behind the id, and mapping straight
     * over it would drop the row — a comment is not lost because we cannot name it.</p>
     */
    private PostCommentResponse toCommentResponse(PostComment comment, Long readerAccountId, DisplayNameReader.Brief brief) {
        return new PostCommentResponse(
                comment.getId(),
                comment.getPostId(),
                comment.getAuthorAccountId(),
                comment.getContent(),
                comment.getIsHidden(),
                comment.getCreatedAt(),
                comment.getUpdatedAt(),
                comment.getAuthorAccountId().equals(readerAccountId),
                brief == null ? null : brief.maskedName(),
                brief == null ? null : brief.photo());
    }

    private Mono<PostCommentResponse> toCommentResponse(PostComment comment, Long readerAccountId) {
        return displayNames.briefsOf(List.of(comment.getAuthorAccountId()))
                .defaultIfEmpty(Map.of())
                .map(briefs -> toCommentResponse(comment, readerAccountId, briefs.get(comment.getAuthorAccountId())));
    }

    /**
     * Reports a post. The duplicate check is the unique constraint itself, not
     * a separate exists-then-insert that a second request could race past: the
     * insert is attempted directly and a constraint violation becomes 409.
     * Reaching the auto-hide threshold is safe under concurrency because
     * hiding is an idempotent update, not a counter of its own.
     */
    @Transactional
    public Mono<PostReportResponse> reportPost(Long postId, Long reporterAccountId, PostReportRequest request) {
        return rateLimiter.check(reporterAccountId)
                .then(scopeAuthorizer.scopeOf(reporterAccountId))
                .flatMap(scope -> requireVisible(postId, scope))
                .flatMap(post -> {
                    PostReport report = new PostReport();
                    report.setPostId(postId);
                    report.setReporterAccountId(reporterAccountId);
                    report.setReason(request.reason().trim());
                    report.setCreatedAt(LocalDateTime.now(clock));
                    return reportRepository.save(report)
                            .onErrorMap(DataIntegrityViolationException.class,
                                    e -> ApiException.conflict("You have already reported this post"));
                })
                .flatMap(savedReport -> reportRepository.countByPostId(postId)
                        .flatMap(count -> {
                            if (count >= AUTO_HIDE_REPORT_THRESHOLD) {
                                return systemHide(postId).thenReturn(savedReport);
                            }
                            return Mono.just(savedReport);
                        }))
                .map(saved -> new PostReportResponse(
                        saved.getId(),
                        saved.getPostId(),
                        saved.getReporterAccountId(),
                        saved.getReason(),
                        saved.getCreatedAt()
                ));
    }

    /**
     * Hides a post. A TEACHER may only hide a post in a class they teach; an
     * ADMIN may hide any. Either way the post still has to exist, and a post
     * outside the caller's reach answers 404, the same as an unknown id -
     * which class exists where is not something to reveal to a caller who
     * cannot act on it.
     */
    @Transactional
    public Mono<Void> hidePost(Long postId, Long moderatorAccountId, boolean admin) {
        return moderatorClassIds(moderatorAccountId, admin)
                .flatMap(classIds -> requireModerationTarget(postId, classIds, admin))
                .flatMap(post -> {
                    post.hide(moderatorAccountId, LocalDateTime.now(clock));
                    return postRepository.save(post);
                })
                .then();
    }

    /** The counterpart of {@link #hidePost}: a moderator may also show a post again. */
    @Transactional
    public Mono<Void> unhidePost(Long postId, Long moderatorAccountId, boolean admin) {
        return moderatorClassIds(moderatorAccountId, admin)
                .flatMap(classIds -> requireModerationTarget(postId, classIds, admin))
                .flatMap(post -> {
                    post.unhide();
                    return postRepository.save(post);
                })
                .then();
    }

    /**
     * Hides a comment (issue #313). Moderation of a comment is moderation of the
     * post it hangs from: the same rule decides who may act - a TEACHER only in
     * a class they teach, an ADMIN anywhere - and it is asked the same way, of
     * the post the URL names. A teacher who cannot reach the post therefore
     * cannot reach the comment either, and both answer 404 rather than 403 so
     * that guessing an id never confirms that it exists.
     *
     * <p>Hiding twice is the same as hiding once: the flag is written, not
     * counted, so a second call costs one more update and changes nothing.</p>
     */
    @Transactional
    public Mono<Void> hideComment(Long postId, Long commentId, Long moderatorAccountId, boolean admin) {
        return moderatorClassIds(moderatorAccountId, admin)
                .flatMap(classIds -> requireModerationTarget(postId, classIds, admin))
                .flatMap(post -> requireCommentTarget(postId, commentId))
                .flatMap(comment -> {
                    comment.hide();
                    return commentRepository.save(comment);
                })
                .then();
    }

    /** The counterpart of {@link #hideComment}: a comment taken down can be put back. */
    @Transactional
    public Mono<Void> unhideComment(Long postId, Long commentId, Long moderatorAccountId, boolean admin) {
        return moderatorClassIds(moderatorAccountId, admin)
                .flatMap(classIds -> requireModerationTarget(postId, classIds, admin))
                .flatMap(post -> requireCommentTarget(postId, commentId))
                .flatMap(comment -> {
                    comment.unhide();
                    return commentRepository.save(comment);
                })
                .then();
    }

    /** The reported-post queue a moderator works from: their own classes for a TEACHER, every school for an ADMIN. */
    public Flux<ReportedPostResponse> listReported(Long moderatorAccountId, boolean admin) {
        Flux<PostFeedRepository.ReportedPost> reported = admin
                ? feedRepository.findAllReported()
                : scopeAuthorizer.taughtClassesOf(moderatorAccountId).flatMapMany(classIds ->
                        classIds.isEmpty() ? Flux.empty() : feedRepository.findReported(classIds));
        return reported.map(row -> new ReportedPostResponse(
                row.postId(), row.authorAccountId(), row.classId(), row.content(),
                row.isHidden(), row.reportCount(), row.createdAt()));
    }

    private Mono<Set<Long>> moderatorClassIds(Long moderatorAccountId, boolean admin) {
        return admin ? Mono.just(Set.of()) : scopeAuthorizer.taughtClassesOf(moderatorAccountId);
    }

    private Mono<Post> requireModerationTarget(Long postId, Set<Long> classIds, boolean admin) {
        return postRepository.findByIdAndDeletedAtIsNull(postId)
                .filter(post -> admin || classIds.contains(post.getClassId()))
                .switchIfEmpty(Mono.error(ApiException.notFound("Post not found")));
    }

    /**
     * The comment to act on, once the post it belongs to has already been
     * cleared for this moderator. A comment that does not exist, one that is
     * not this post's, and one that was deleted all answer the same 404 - the
     * three are indistinguishable on purpose.
     */
    private Mono<PostComment> requireCommentTarget(Long postId, Long commentId) {
        return commentRepository.findByIdAndPostIdAndDeletedAtIsNull(commentId, postId)
                .switchIfEmpty(Mono.error(ApiException.notFound("Comment not found")));
    }

    /** Used only by the auto-hide path, which already knows the post is visible and in range. */
    private Mono<Void> systemHide(Long postId) {
        return postRepository.findByIdAndDeletedAtIsNull(postId)
                .flatMap(post -> {
                    post.hide(null, LocalDateTime.now(clock));
                    return postRepository.save(post);
                })
                .then();
    }

    private Mono<Post> requireVisible(Long postId, FeedScope scope) {
        return postRepository.findByIdAndIsHiddenFalseAndDeletedAtIsNull(postId)
                .filter(post -> scope.covers(
                        post.getInstitutionId(), post.getClassId(), post.getCourseId(), post.getSchoolYearId()))
                .switchIfEmpty(Mono.error(ApiException.notFound("Post not found")));
    }

    private Mono<ClassScope> requireClassScope(Long accountId, Long classId) {
        return scopeAuthorizer.classScope(accountId, classId)
                .switchIfEmpty(Mono.error(ApiException.notFound(
                        "Class not found, or you do not belong to it")));
    }

    /** A requested id that is not already inside the caller's scope is refused, not silently dropped. */
    private void requireWithinScope(FeedScope scope, Long schoolYearId, Long courseId, Long classId) {
        if (classId != null && !scope.classIds().contains(classId)
                || courseId != null && !scope.courseIds().contains(courseId)
                || schoolYearId != null && !scope.schoolYearIds().contains(schoolYearId)) {
            throw ApiException.notFound("Class, course or school year not found, or you do not belong to it");
        }
    }

    private void validatePagination(int page, int limit) {
        if (page < 1) {
            throw ApiException.badRequest("page must be at least 1");
        }
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw ApiException.badRequest("limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT);
        }
    }

    private Mono<PostResponse> mapToResponse(Post post, Long currentAccountId) {
        Mono<Long> commentCountMono = commentRepository.countByPostIdAndIsHiddenFalseAndDeletedAtIsNull(post.getId());
        Mono<Boolean> hasReactedMono = reactionRepository.existsByPostIdAndAccountId(post.getId(), currentAccountId);

        return Mono.zip(commentCountMono, hasReactedMono)
                .map(tuple -> new PostResponse(
                        post.getId(),
                        post.getAuthorAccountId(),
                        post.getInstitutionId(),
                        post.getSchoolYearId(),
                        post.getCourseId(),
                        post.getClassId(),
                        post.getType(),
                        post.getTitle(),
                        post.getContent(),
                        post.getIsAutoGenerated(),
                        tuple.getT1(), // commentCount
                        tuple.getT2(), // hasReacted
                        post.getCreatedAt(),
                        post.getUpdatedAt(),
                        post.getSimulationId() == null ? null : new PostResponse.SimulationCard(
                                post.getSimulationId(), post.getStatementTitle(), post.getSchoolName(),
                                post.getFinalScore(), post.getMaxScore(), post.getCorrectAnswers(),
                                post.getTotalQuestions(), post.getTimeSpentSeconds()),
                        PostBlocks.fromJson(post.getBlocks())
                ));
    }
}
