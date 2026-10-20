package ao.creativemode.kixi.feed.service;

import ao.creativemode.kixi.feed.dto.post.PostRequest;
import ao.creativemode.kixi.feed.dto.postComment.PostCommentRequest;
import ao.creativemode.kixi.feed.dto.postComment.PostCommentResponse;
import ao.creativemode.kixi.feed.dto.postReaction.PostReactionRequest;
import ao.creativemode.kixi.feed.dto.postReaction.PostReactionSetRequest;
import ao.creativemode.kixi.feed.dto.postReport.PostReportRequest;
import ao.creativemode.kixi.feed.enums.PostReactionType;
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
import ao.creativemode.kixi.shared.service.FeedScopeAuthorizer;
import ao.creativemode.kixi.feed.dto.postComment.PostCommentResponse;
import ao.creativemode.kixi.feed.model.PostComment;
import ao.creativemode.kixi.shared.service.DisplayNameReader;
import ao.creativemode.kixi.shared.service.InstitutionNameReader;
import ao.creativemode.kixi.shared.service.SimulationShowcaseReader;
import ao.creativemode.kixi.shared.service.FeedScopeAuthorizer.ClassScope;
import ao.creativemode.kixi.shared.service.FeedScopeAuthorizer.FeedScope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Issue #120. These tests are written against the authorization boundary
 * itself, not against an already-filtered query: every scenario that found a
 * real hole in the earlier version - a feed with no parameters, a forged id,
 * acting on another school's post, a teacher reaching outside their own
 * classes - is reproduced here against {@link FeedScopeAuthorizer}, the way
 * the review asked for.
 */
@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final Long OTHER_ACCOUNT_ID = 2L;
    private static final Long OTHER_SCHOOL_CLASS_ID = 99L;
    private static final Long MY_CLASS_ID = 10L;
    private static final Long MY_COURSE_ID = 100L;
    private static final Long MY_SCHOOL_YEAR_ID = 2026L;
    private static final Long MY_INSTITUTION_ID = 1L;
    private static final Long OTHER_INSTITUTION_ID = 2L;
    private static final Long POST_ID = 500L;
    private static final Long COMMENT_ID = 900L;

    @Mock private PostRepository postRepository;
    @Mock private PostFeedRepository feedRepository;
    @Mock private PostReactionRepository reactionRepository;
    @Mock private PostCommentRepository commentRepository;
    @Mock private DisplayNameReader displayNames;
    @Mock private PostReportRepository reportRepository;
    @Mock private FeedScopeAuthorizer scopeAuthorizer;
    @Mock private FeedRateLimiter rateLimiter;
    @Mock private SimulationShowcaseReader showcaseReader;
    @Mock private InstitutionNameReader institutionNames;

    private PostService postService;

    private final Clock clock = Clock.fixed(
            LocalDateTime.of(2026, 10, 11, 12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        postService = new PostService(postRepository, feedRepository, reactionRepository,
                commentRepository, displayNames, reportRepository, scopeAuthorizer,
                rateLimiter, showcaseReader, institutionNames, clock);
        lenient().when(displayNames.maskedNameOf(anyLong()))
                .thenAnswer(call -> Mono.just(maskedNameOf((Long) call.getArgument(0))));
        lenient().when(rateLimiter.check(anyLong())).thenReturn(Mono.empty());
    }

    /** The masked name the door hands back for whoever asked. */
    private String maskedNameOf(Long accountId) {
        return accountId.equals(ACCOUNT_ID) ? "Ana N." : "Bruno S.";
    }

    /** A comment as the repository would hand it back. */
    private PostComment comment(Long id, Long authorAccountId, String content, int minute) {
        PostComment comment = new PostComment();
        comment.setId(id);
        comment.setPostId(POST_ID);
        comment.setAuthorAccountId(authorAccountId);
        comment.setContent(content);
        comment.setIsHidden(false);
        comment.setCreatedAt(LocalDateTime.now(clock).withSecond(0).withNano(0).plusMinutes(minute));
        comment.setUpdatedAt(comment.getCreatedAt());
        return comment;
    }

    private FeedScope myScope() {
        return new FeedScope(
                Set.of(MY_INSTITUTION_ID), Set.of(MY_CLASS_ID), Set.of(MY_COURSE_ID), Set.of(MY_SCHOOL_YEAR_ID));
    }

    private Post myPost() {
        Post post = new Post();
        post.setId(POST_ID);
        post.setAuthorAccountId(ACCOUNT_ID);
        post.setInstitutionId(MY_INSTITUTION_ID);
        post.setSchoolYearId(MY_SCHOOL_YEAR_ID);
        post.setCourseId(MY_COURSE_ID);
        post.setClassId(MY_CLASS_ID);
        post.setType(PostType.TOPIC.getValue());
        post.setContent("Sample post content");
        post.setIsHidden(false);
        post.setIsAutoGenerated(false);
        post.setCreatedAt(LocalDateTime.now(clock));
        post.setUpdatedAt(LocalDateTime.now(clock));
        return post;
    }

    private Post otherSchoolPost() {
        Post post = myPost();
        post.setInstitutionId(OTHER_INSTITUTION_ID);
        post.setClassId(OTHER_SCHOOL_CLASS_ID);
        post.setCourseId(999L);
        post.setSchoolYearId(3000L);
        return post;
    }

    /** A comment under {@link #POST_ID}, the way the repository hands it back for moderation. */
    private PostComment myComment(boolean hidden) {
        PostComment comment = new PostComment();
        comment.setId(COMMENT_ID);
        comment.setPostId(POST_ID);
        comment.setAuthorAccountId(ACCOUNT_ID);
        comment.setContent("A comment");
        comment.setIsHidden(hidden);
        comment.setCreatedAt(LocalDateTime.now(clock));
        comment.setUpdatedAt(LocalDateTime.now(clock));
        return comment;
    }

    // ── Feed: isolation ─────────────────────────────────────────────────────

    @Test
    void feedWithNoParametersOnlyEverTouchesTheCallersOwnScope() {
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(feedRepository.findFeed(eq(myScope()), isNull(), isNull(), isNull(), eq(10), eq(0L)))
                .thenReturn(Flux.just(myPost()));
        when(commentRepository.countByPostIdAndIsHiddenFalseAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(0L));
        when(reactionRepository.existsByPostIdAndAccountId(POST_ID, ACCOUNT_ID)).thenReturn(Mono.just(false));

        StepVerifier.create(postService.getFeed(ACCOUNT_ID, null, null, null, 1, 10))
                .expectNextCount(1)
                .verifyComplete();

        verify(feedRepository).findFeed(eq(myScope()), isNull(), isNull(), isNull(), eq(10), eq(0L));
    }

    @Test
    void aForgedClassIdOutsideTheCallersScopeIsRefused() {
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));

        StepVerifier.create(postService.getFeed(ACCOUNT_ID, null, null, OTHER_SCHOOL_CLASS_ID, 1, 10))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verifyNoInteractions(feedRepository);
    }

    @Test
    void aForgedSchoolYearOutsideTheCallersScopeIsRefused() {
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));

        StepVerifier.create(postService.getFeed(ACCOUNT_ID, 1999L, null, null, 1, 10))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void aClassIdInsideTheCallersScopeIsAcceptedAsANarrowingFilter() {
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(feedRepository.findFeed(any(), isNull(), isNull(), eq(MY_CLASS_ID), eq(10), eq(0L)))
                .thenReturn(Flux.empty());

        StepVerifier.create(postService.getFeed(ACCOUNT_ID, null, null, MY_CLASS_ID, 1, 10))
                .verifyComplete();
    }

    @Test
    void feedPreservesTheOrderFindFeedReturnedDespiteThePerPostEnrichmentCalls() {
        Post first = myPost();
        first.setId(1L);
        first.setContent("first");
        Post second = myPost();
        second.setId(2L);
        second.setContent("second");

        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(feedRepository.findFeed(any(), any(), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.just(first, second));
        // The second post resolves its enrichment data slower than the first, so a
        // getFeed that merged results by completion order (flatMap) rather than by
        // request order (flatMapSequential) would show "second" before "first".
        when(commentRepository.countByPostIdAndIsHiddenFalseAndDeletedAtIsNull(1L))
                .thenReturn(Mono.just(0L).delayElement(java.time.Duration.ofMillis(50)));
        when(commentRepository.countByPostIdAndIsHiddenFalseAndDeletedAtIsNull(2L)).thenReturn(Mono.just(0L));
        when(reactionRepository.existsByPostIdAndAccountId(anyLong(), eq(ACCOUNT_ID))).thenReturn(Mono.just(false));

        List<String> order = postService.getFeed(ACCOUNT_ID, null, null, null, 1, 10)
                .map(response -> response.content())
                .collectList()
                .block(java.time.Duration.ofSeconds(2));

        assertThat(order).containsExactly("first", "second");
    }

    @Test
    void pageMustBeAtLeastOne() {
        StepVerifier.create(postService.getFeed(ACCOUNT_ID, null, null, null, 0, 10))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
        verifyNoInteractions(scopeAuthorizer);
    }

    @Test
    void limitAboveFiftyIsRejected() {
        StepVerifier.create(postService.getFeed(ACCOUNT_ID, null, null, null, 1, 100000))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
    }

    @Test
    void limitBelowOneIsRejected() {
        StepVerifier.create(postService.getFeed(ACCOUNT_ID, null, null, null, 1, 0))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
    }

    // ── Creating a post ─────────────────────────────────────────────────────

    @Test
    void createPostDerivesTheWholeScopeFromTheClassTheAccountActuallyHolds() {
        PostRequest request = new PostRequest(PostType.TOPIC, MY_CLASS_ID, "Title", "Sample test post content", null);
        when(scopeAuthorizer.classScope(ACCOUNT_ID, MY_CLASS_ID)).thenReturn(Mono.just(
                new ClassScope(MY_CLASS_ID, MY_INSTITUTION_ID, MY_COURSE_ID, MY_SCHOOL_YEAR_ID)));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(commentRepository.countByPostIdAndIsHiddenFalseAndDeletedAtIsNull(any())).thenReturn(Mono.just(0L));
        when(reactionRepository.existsByPostIdAndAccountId(any(), eq(ACCOUNT_ID))).thenReturn(Mono.just(false));

        StepVerifier.create(postService.createPost(ACCOUNT_ID, request))
                .assertNext(response -> {
                    assertThat(response.institutionId()).isEqualTo(MY_INSTITUTION_ID);
                    assertThat(response.courseId()).isEqualTo(MY_COURSE_ID);
                    assertThat(response.schoolYearId()).isEqualTo(MY_SCHOOL_YEAR_ID);
                    assertThat(response.classId()).isEqualTo(MY_CLASS_ID);
                })
                .verifyComplete();

        verify(postRepository).save(argThat(post ->
                post.getInstitutionId().equals(MY_INSTITUTION_ID) && post.getClassId().equals(MY_CLASS_ID)));
    }

    @Test
    void postingIntoAClassTheAccountDoesNotHoldIsRefused() {
        PostRequest request = new PostRequest(PostType.TOPIC, OTHER_SCHOOL_CLASS_ID, null, "Sample test post content", null);
        when(scopeAuthorizer.classScope(ACCOUNT_ID, OTHER_SCHOOL_CLASS_ID)).thenReturn(Mono.empty());

        StepVerifier.create(postService.createPost(ACCOUNT_ID, request))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verifyNoInteractions(postRepository);
    }

    @Test
    void onlyDoubtAndTopicCanBeCreatedManually() {
        PostRequest request = new PostRequest(PostType.EXAM, MY_CLASS_ID, null, "Sample test post content", null);

        StepVerifier.create(postService.createPost(ACCOUNT_ID, request))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
        verifyNoInteractions(scopeAuthorizer);
    }

    @Test
    void aSimulationPostCarriesTheFiguresTheServerReadNotTheOnesTheClientSent() {
        PostRequest request = new PostRequest(PostType.SIMULATION, MY_CLASS_ID, null, "Corri melhor do que esperava", 77L);
        when(scopeAuthorizer.classScope(ACCOUNT_ID, MY_CLASS_ID)).thenReturn(Mono.just(
                new ClassScope(MY_CLASS_ID, MY_INSTITUTION_ID, MY_COURSE_ID, MY_SCHOOL_YEAR_ID)));
        when(showcaseReader.showcaseOf(77L, ACCOUNT_ID)).thenReturn(Mono.just(
                new SimulationShowcaseReader.Showcase(77L, "Exame de Matemática", 14.5, 20.0, 12, 15, 1800)));
        when(institutionNames.nameOf(MY_INSTITUTION_ID)).thenReturn(Mono.just("Colégio Kixi"));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(commentRepository.countByPostIdAndIsHiddenFalseAndDeletedAtIsNull(any())).thenReturn(Mono.just(0L));
        when(reactionRepository.existsByPostIdAndAccountId(any(), eq(ACCOUNT_ID))).thenReturn(Mono.just(false));

        StepVerifier.create(postService.createPost(ACCOUNT_ID, request))
                .assertNext(response -> {
                    assertThat(response.type()).isEqualTo("simulation");
                    assertThat(response.content()).isEqualTo("Corri melhor do que esperava");
                    assertThat(response.simulation().finalScore()).isEqualTo(14.5);
                    assertThat(response.simulation().maxScore()).isEqualTo(20.0);
                    assertThat(response.simulation().schoolName()).isEqualTo("Colégio Kixi");
                    assertThat(response.simulation().statementTitle()).isEqualTo("Exame de Matemática");
                })
                .verifyComplete();
    }

    @Test
    void aSimulationPostWithoutANoteIsAllowedButWithoutASimulationIsNot() {
        when(scopeAuthorizer.classScope(ACCOUNT_ID, MY_CLASS_ID)).thenReturn(Mono.just(
                new ClassScope(MY_CLASS_ID, MY_INSTITUTION_ID, MY_COURSE_ID, MY_SCHOOL_YEAR_ID)));
        when(showcaseReader.showcaseOf(77L, ACCOUNT_ID)).thenReturn(Mono.just(
                new SimulationShowcaseReader.Showcase(77L, "Exame", 10.0, 20.0, 5, 10, 600)));
        when(institutionNames.nameOf(MY_INSTITUTION_ID)).thenReturn(Mono.empty());
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(commentRepository.countByPostIdAndIsHiddenFalseAndDeletedAtIsNull(any())).thenReturn(Mono.just(0L));
        when(reactionRepository.existsByPostIdAndAccountId(any(), eq(ACCOUNT_ID))).thenReturn(Mono.just(false));

        StepVerifier.create(postService.createPost(ACCOUNT_ID,
                        new PostRequest(PostType.SIMULATION, MY_CLASS_ID, null, null, 77L)))
                .assertNext(response -> assertThat(response.content()).isEmpty())
                .verifyComplete();

        StepVerifier.create(postService.createPost(ACCOUNT_ID,
                        new PostRequest(PostType.SIMULATION, MY_CLASS_ID, null, "nota", null)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
    }

    @Test
    void aSimulationThatIsNotTheCallersOrStillRunningIsNotPublished() {
        when(scopeAuthorizer.classScope(ACCOUNT_ID, MY_CLASS_ID)).thenReturn(Mono.just(
                new ClassScope(MY_CLASS_ID, MY_INSTITUTION_ID, MY_COURSE_ID, MY_SCHOOL_YEAR_ID)));
        when(showcaseReader.showcaseOf(88L, ACCOUNT_ID)).thenReturn(Mono.error(ApiException.notFound("Simulation not found: 88")));

        StepVerifier.create(postService.createPost(ACCOUNT_ID,
                        new PostRequest(PostType.SIMULATION, MY_CLASS_ID, null, "nota", 88L)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verifyNoInteractions(postRepository);
    }

    @Test
    void aTextPostCannotSmuggleInASimulationId() {
        StepVerifier.create(postService.createPost(ACCOUNT_ID,
                        new PostRequest(PostType.TOPIC, MY_CLASS_ID, null, "Sample test post content", 77L)))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
        verifyNoInteractions(showcaseReader);
    }

    @Test
    void creatingAPostChecksTheRateLimitFirst() {
        PostRequest request = new PostRequest(PostType.DOUBT, MY_CLASS_ID, null, "Sample test post content", null);
        when(rateLimiter.check(ACCOUNT_ID)).thenReturn(Mono.error(
                new ao.creativemode.kixi.feed.exception.FeedRateLimitException(30L)));

        StepVerifier.create(postService.createPost(ACCOUNT_ID, request))
                .expectError(ao.creativemode.kixi.feed.exception.FeedRateLimitException.class)
                .verify();
        verifyNoInteractions(scopeAuthorizer);
    }

    // ── Comments, reactions and reports: scoped to the same school ─────────

    @Test
    void commentingOnAnotherSchoolsPostIs404() {
        PostCommentRequest request = new PostCommentRequest("A comment");
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(postRepository.findByIdAndIsHiddenFalseAndDeletedAtIsNull(POST_ID))
                .thenReturn(Mono.just(otherSchoolPost()));

        StepVerifier.create(postService.addComment(POST_ID, ACCOUNT_ID, request))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verifyNoInteractions(commentRepository);
    }

    // === reading the conversation ===========================================

    /**
     * The conversation, oldest first, with a name on each row.
     *
     * <p>Chronological because the issue is a student answering what came before: a page of
     * the newest comments would show the end of a conversation without its beginning.</p>
     */
    @Test
    void readingTheConversationReturnsItOldestFirstWithNames() {
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(postRepository.findByIdAndIsHiddenFalseAndDeletedAtIsNull(POST_ID))
                .thenReturn(Mono.just(myPost()));
        when(commentRepository
                .findByPostIdAndIsHiddenFalseAndDeletedAtIsNullOrderByCreatedAtAsc(
                        eq(POST_ID), any(Pageable.class)))
                .thenReturn(Flux.just(
                        comment(1L, OTHER_ACCOUNT_ID, "Primeiro", 0),
                        comment(2L, ACCOUNT_ID, "Segundo", 1)));

        List<PostCommentResponse> comments = postService
                .listComments(POST_ID, ACCOUNT_ID, 1, 20).collectList().block();

        assertThat(comments).extracting(PostCommentResponse::content)
                .containsExactly("Primeiro", "Segundo");
        assertThat(comments).extracting(PostCommentResponse::displayName)
                .containsExactly("Bruno S.", "Ana N.");
    }

    /** The author's own row is marked, so the client does not compare ids itself. */
    @Test
    void theReadersOwnCommentIsMarked() {
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(postRepository.findByIdAndIsHiddenFalseAndDeletedAtIsNull(POST_ID))
                .thenReturn(Mono.just(myPost()));
        when(commentRepository
                .findByPostIdAndIsHiddenFalseAndDeletedAtIsNullOrderByCreatedAtAsc(
                        eq(POST_ID), any(Pageable.class)))
                .thenReturn(Flux.just(
                        comment(1L, OTHER_ACCOUNT_ID, "Do colega", 0),
                        comment(2L, ACCOUNT_ID, "Meu", 1)));

        List<PostCommentResponse> comments = postService
                .listComments(POST_ID, ACCOUNT_ID, 1, 20).collectList().block();

        assertThat(comments).extracting(PostCommentResponse::mine)
                .containsExactly(false, true);
    }

    /**
     * A comment whose author has no profile still comes back, without a name.
     *
     * <p>The door returns an empty Mono for a missing profile. Mapping straight over it
     * would drop the row, and a comment is not lost because we cannot name it.</p>
     */
    @Test
    void aCommentWithNoProfileStillComesBack() {
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(postRepository.findByIdAndIsHiddenFalseAndDeletedAtIsNull(POST_ID))
                .thenReturn(Mono.just(myPost()));
        when(displayNames.maskedNameOf(OTHER_ACCOUNT_ID)).thenReturn(Mono.empty());
        when(commentRepository
                .findByPostIdAndIsHiddenFalseAndDeletedAtIsNullOrderByCreatedAtAsc(
                        eq(POST_ID), any(Pageable.class)))
                .thenReturn(Flux.just(comment(1L, OTHER_ACCOUNT_ID, "Sem perfil", 0)));

        List<PostCommentResponse> comments = postService
                .listComments(POST_ID, ACCOUNT_ID, 1, 20).collectList().block();

        assertThat(comments).extracting(PostCommentResponse::content).containsExactly("Sem perfil");
        assertThat(comments.get(0).displayName()).isNull();
    }

    /**
     * Another school's post is 404, not an empty list.
     *
     * <p>The id arrives in the URL and a student who guesses one must not read what was
     * written under it. An empty list would say "there is nothing here" and 403 would say
     * "there is something and it is not yours".</p>
     */
    @Test
    void readingAnotherSchoolsConversationIs404() {
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(postRepository.findByIdAndIsHiddenFalseAndDeletedAtIsNull(POST_ID))
                .thenReturn(Mono.just(otherSchoolPost()));

        StepVerifier.create(postService.listComments(POST_ID, ACCOUNT_ID, 1, 20))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verifyNoInteractions(commentRepository);
    }

    /** A read is not a write; the limiter that stops comment spam stays off it. */
    @Test
    void readingTheConversationIsNotRateLimited() {
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(postRepository.findByIdAndIsHiddenFalseAndDeletedAtIsNull(POST_ID))
                .thenReturn(Mono.just(myPost()));
        when(commentRepository
                .findByPostIdAndIsHiddenFalseAndDeletedAtIsNullOrderByCreatedAtAsc(
                        eq(POST_ID), any(Pageable.class)))
                .thenReturn(Flux.empty());

        postService.listComments(POST_ID, ACCOUNT_ID, 1, 20).collectList().block();

        verify(rateLimiter, never()).check(anyLong());
    }

    @Test
    void aConversationPageIsBounded() {
        // Validated before the scope is resolved, so an unusable page never costs a query.
        StepVerifier.create(postService.listComments(POST_ID, ACCOUNT_ID, 0, 20))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
        StepVerifier.create(postService.listComments(POST_ID, ACCOUNT_ID, 1, 5000))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
    }

    /** The page asked for is the page asked for, offset included. */
    @Test
    void theSecondPageIsTheSecondPage() {
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(postRepository.findByIdAndIsHiddenFalseAndDeletedAtIsNull(POST_ID))
                .thenReturn(Mono.just(myPost()));
        when(commentRepository
                .findByPostIdAndIsHiddenFalseAndDeletedAtIsNullOrderByCreatedAtAsc(
                        eq(POST_ID), any(Pageable.class)))
                .thenReturn(Flux.empty());

        postService.listComments(POST_ID, ACCOUNT_ID, 3, 10).collectList().block();

        verify(commentRepository)
                .findByPostIdAndIsHiddenFalseAndDeletedAtIsNullOrderByCreatedAtAsc(
                        eq(POST_ID), eq(PageRequest.of(2, 10)));
    }

    @Test
    void reactingToAnotherSchoolsPostIs404() {
        PostReactionRequest request = new PostReactionRequest(PostReactionType.APPLAUSE);
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(postRepository.findByIdAndIsHiddenFalseAndDeletedAtIsNull(POST_ID))
                .thenReturn(Mono.just(otherSchoolPost()));

        StepVerifier.create(postService.toggleReaction(POST_ID, ACCOUNT_ID, request))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verifyNoInteractions(reactionRepository);
    }

    @Test
    void reportingAnotherSchoolsPostIs404() {
        PostReportRequest request = new PostReportRequest("Offensive content");
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(postRepository.findByIdAndIsHiddenFalseAndDeletedAtIsNull(POST_ID))
                .thenReturn(Mono.just(otherSchoolPost()));

        StepVerifier.create(postService.reportPost(POST_ID, ACCOUNT_ID, request))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verifyNoInteractions(reportRepository);
    }

    @Test
    void anUnknownPostIdIsAlso404SoAForgedIdLearnsNothing() {
        PostCommentRequest request = new PostCommentRequest("A comment");
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(postRepository.findByIdAndIsHiddenFalseAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.empty());

        StepVerifier.create(postService.addComment(POST_ID, ACCOUNT_ID, request))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    // ── Reactions: toggle correctness ───────────────────────────────────────

    @Test
    void togglingAReactionThatExistsRemovesIt() {
        PostReactionRequest request = new PostReactionRequest(PostReactionType.APPLAUSE);
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(postRepository.findByIdAndIsHiddenFalseAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(myPost()));
        when(reactionRepository.deleteReaction(POST_ID, ACCOUNT_ID, "applause")).thenReturn(Mono.just(1));

        StepVerifier.create(postService.toggleReaction(POST_ID, ACCOUNT_ID, request))
                .assertNext(response -> assertThat(response.reacted()).isFalse())
                .verifyComplete();
        verify(reactionRepository, never()).insertReaction(any(), any(), any());
    }

    @Test
    void togglingAReactionThatDoesNotExistCreatesIt() {
        PostReactionRequest request = new PostReactionRequest(PostReactionType.APPLAUSE);
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(postRepository.findByIdAndIsHiddenFalseAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(myPost()));
        when(reactionRepository.deleteReaction(POST_ID, ACCOUNT_ID, "applause")).thenReturn(Mono.just(0));
        when(reactionRepository.insertReaction(POST_ID, ACCOUNT_ID, "applause")).thenReturn(Mono.just(1));

        StepVerifier.create(postService.toggleReaction(POST_ID, ACCOUNT_ID, request))
                .assertNext(response -> assertThat(response.reacted()).isTrue())
                .verifyComplete();
    }

    @Test
    void settingAReactionToPresentIsIdempotent() {
        PostReactionSetRequest request = new PostReactionSetRequest(PostReactionType.APPLAUSE, true);
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(postRepository.findByIdAndIsHiddenFalseAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(myPost()));
        when(reactionRepository.insertReaction(POST_ID, ACCOUNT_ID, "applause")).thenReturn(Mono.just(0));

        StepVerifier.create(postService.setReaction(POST_ID, ACCOUNT_ID, request))
                .assertNext(response -> assertThat(response.reacted()).isTrue())
                .verifyComplete();
        verify(reactionRepository).insertReaction(POST_ID, ACCOUNT_ID, "applause");
        verify(reactionRepository, never()).deleteReaction(any(), any(), any());
    }

    @Test
    void settingAReactionToAbsentIsIdempotent() {
        PostReactionSetRequest request = new PostReactionSetRequest(PostReactionType.APPLAUSE, false);
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(postRepository.findByIdAndIsHiddenFalseAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(myPost()));
        when(reactionRepository.deleteReaction(POST_ID, ACCOUNT_ID, "applause")).thenReturn(Mono.just(0));

        StepVerifier.create(postService.setReaction(POST_ID, ACCOUNT_ID, request))
                .assertNext(response -> assertThat(response.reacted()).isFalse())
                .verifyComplete();
        verify(reactionRepository).deleteReaction(POST_ID, ACCOUNT_ID, "applause");
        verify(reactionRepository, never()).insertReaction(any(), any(), any());
    }

    // ── Reports and auto-hide ────────────────────────────────────────────────

    @Test
    void reportingAPostTwiceIsAConflictNotADuplicateRow() {
        PostReportRequest request = new PostReportRequest("Offensive content");
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(postRepository.findByIdAndIsHiddenFalseAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(myPost()));
        when(reportRepository.save(any(PostReport.class))).thenReturn(Mono.error(
                new org.springframework.dao.DataIntegrityViolationException("duplicate key")));

        StepVerifier.create(postService.reportPost(POST_ID, ACCOUNT_ID, request))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .verify();
    }

    @Test
    void reportPost_ShouldAutoHidePost_WhenThresholdReached() {
        PostReportRequest request = new PostReportRequest("Offensive content");
        Post post = myPost();
        when(scopeAuthorizer.scopeOf(ACCOUNT_ID)).thenReturn(Mono.just(myScope()));
        when(postRepository.findByIdAndIsHiddenFalseAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(post));
        when(reportRepository.save(any(PostReport.class))).thenReturn(Mono.just(new PostReport()));
        when(reportRepository.countByPostId(POST_ID)).thenReturn(Mono.just(5L));
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(post));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(postService.reportPost(POST_ID, ACCOUNT_ID, request))
                .expectNextCount(1)
                .verifyComplete();

        verify(postRepository).save(argThat(Post::getIsHidden));
    }

    // ── Moderation ───────────────────────────────────────────────────────────

    @Test
    void aTeacherCanOnlyHidePostsInClassesTheyTeach() {
        Long teacherId = 7L;
        when(scopeAuthorizer.taughtClassesOf(teacherId)).thenReturn(Mono.just(Set.of(MY_CLASS_ID)));
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(otherSchoolPost()));

        StepVerifier.create(postService.hidePost(POST_ID, teacherId, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verify(postRepository, never()).save(any());
    }

    @Test
    void aTeacherCanHideAPostInTheirOwnClass() {
        Long teacherId = 7L;
        Post post = myPost();
        when(scopeAuthorizer.taughtClassesOf(teacherId)).thenReturn(Mono.just(Set.of(MY_CLASS_ID)));
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(post));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(postService.hidePost(POST_ID, teacherId, false)).verifyComplete();

        verify(postRepository).save(argThat(saved -> saved.getIsHidden() && saved.getHiddenByAccountId().equals(teacherId)));
    }

    @Test
    void anAdminCanHideAPostInAnySchool() {
        Long adminId = 1000L;
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(otherSchoolPost()));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(postService.hidePost(POST_ID, adminId, true)).verifyComplete();
        verifyNoInteractions(scopeAuthorizer);
    }

    @Test
    void listReportedForATeacherIsScopedToTheirOwnClasses() {
        Long teacherId = 7L;
        when(scopeAuthorizer.taughtClassesOf(teacherId)).thenReturn(Mono.just(Set.of(MY_CLASS_ID)));
        when(feedRepository.findReported(Set.of(MY_CLASS_ID))).thenReturn(Flux.just(
                new PostFeedRepository.ReportedPost(POST_ID, ACCOUNT_ID, MY_CLASS_ID, "content", false, 3L,
                        LocalDateTime.now(clock))));

        StepVerifier.create(postService.listReported(teacherId, false))
                .expectNextCount(1)
                .verifyComplete();
        verify(feedRepository, never()).findAllReported();
    }

    @Test
    void listReportedForAnAdminCoversEverySchool() {
        when(feedRepository.findAllReported()).thenReturn(Flux.just(
                new PostFeedRepository.ReportedPost(POST_ID, ACCOUNT_ID, OTHER_SCHOOL_CLASS_ID, "content", false, 3L,
                        LocalDateTime.now(clock))));

        StepVerifier.create(postService.listReported(1000L, true))
                .expectNextCount(1)
                .verifyComplete();
        verifyNoInteractions(scopeAuthorizer);
    }

    // ── Comment moderation (issue #313) ─────────────────────────────────────

    /**
     * A teacher takes down a comment under a post in their own class.
     *
     * <p>The write that matters is {@code is_hidden}: until this call existed the
     * column was written by nobody, so the flag could never be true.</p>
     */
    @Test
    void aTeacherCanHideACommentOnAPostInTheirOwnClass() {
        Long teacherId = 7L;
        when(scopeAuthorizer.taughtClassesOf(teacherId)).thenReturn(Mono.just(Set.of(MY_CLASS_ID)));
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(myPost()));
        when(commentRepository.findByIdAndPostIdAndDeletedAtIsNull(COMMENT_ID, POST_ID))
                .thenReturn(Mono.just(myComment(false)));
        when(commentRepository.save(any(PostComment.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(postService.hideComment(POST_ID, COMMENT_ID, teacherId, false)).verifyComplete();

        verify(commentRepository).save(argThat(saved -> Boolean.TRUE.equals(saved.getIsHidden())));
    }

    /**
     * The same teacher, a post in another school: 404, and the comment is never even looked up.
     *
     * <p>Asking for the comment first would leak its existence through the difference
     * between a 404 for a bad post and one for a bad comment.</p>
     */
    @Test
    void aTeacherCannotHideACommentOnAPostOutsideTheirClasses() {
        Long teacherId = 7L;
        when(scopeAuthorizer.taughtClassesOf(teacherId)).thenReturn(Mono.just(Set.of(MY_CLASS_ID)));
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(otherSchoolPost()));

        StepVerifier.create(postService.hideComment(POST_ID, COMMENT_ID, teacherId, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verifyNoInteractions(commentRepository);
    }

    /** An ADMIN moderates in any school, and is not narrowed by a class list at all. */
    @Test
    void anAdminCanHideACommentInAnySchool() {
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(otherSchoolPost()));
        when(commentRepository.findByIdAndPostIdAndDeletedAtIsNull(COMMENT_ID, POST_ID))
                .thenReturn(Mono.just(myComment(false)));
        when(commentRepository.save(any(PostComment.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(postService.hideComment(POST_ID, COMMENT_ID, 1000L, true)).verifyComplete();

        verify(commentRepository).save(argThat(saved -> Boolean.TRUE.equals(saved.getIsHidden())));
        verifyNoInteractions(scopeAuthorizer);
    }

    /**
     * Hiding a comment that is already hidden is not an error, and hiding is what a second
     * tap has to do: the flag is set, not counted.
     */
    @Test
    void hidingACommentThatIsAlreadyHiddenIsNotAnError() {
        Long teacherId = 7L;
        when(scopeAuthorizer.taughtClassesOf(teacherId)).thenReturn(Mono.just(Set.of(MY_CLASS_ID)));
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(myPost()));
        when(commentRepository.findByIdAndPostIdAndDeletedAtIsNull(COMMENT_ID, POST_ID))
                .thenReturn(Mono.just(myComment(true)));
        when(commentRepository.save(any(PostComment.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(postService.hideComment(POST_ID, COMMENT_ID, teacherId, false)).verifyComplete();

        verify(commentRepository).save(argThat(saved -> Boolean.TRUE.equals(saved.getIsHidden())));
    }

    /** The other half of the round trip: what was taken down comes back. */
    @Test
    void unhidingBringsACommentBack() {
        Long teacherId = 7L;
        when(scopeAuthorizer.taughtClassesOf(teacherId)).thenReturn(Mono.just(Set.of(MY_CLASS_ID)));
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(myPost()));
        when(commentRepository.findByIdAndPostIdAndDeletedAtIsNull(COMMENT_ID, POST_ID))
                .thenReturn(Mono.just(myComment(true)));
        when(commentRepository.save(any(PostComment.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(postService.unhideComment(POST_ID, COMMENT_ID, teacherId, false)).verifyComplete();

        verify(commentRepository).save(argThat(saved -> Boolean.FALSE.equals(saved.getIsHidden())));
    }

    /**
     * A comment id paired with a post it does not hang from is a 404, whatever it is.
     *
     * <p>Ids are guessable: without {@code postId} in the lookup, a moderator with access
     * to one post could take down a comment of another post in the same school - or,
     * worse, be made to think they had.</p>
     */
    @Test
    void aCommentThatIsNotOnThePostInTheUrlIs404() {
        Long teacherId = 7L;
        when(scopeAuthorizer.taughtClassesOf(teacherId)).thenReturn(Mono.just(Set.of(MY_CLASS_ID)));
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(myPost()));
        when(commentRepository.findByIdAndPostIdAndDeletedAtIsNull(COMMENT_ID, POST_ID)).thenReturn(Mono.empty());

        StepVerifier.create(postService.hideComment(POST_ID, COMMENT_ID, teacherId, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verify(commentRepository, never()).save(any());
    }

    /** An unknown post is 404 as well, and the comment table is not touched. */
    @Test
    void hidingACommentOnAnUnknownPostIs404() {
        Long teacherId = 7L;
        when(scopeAuthorizer.taughtClassesOf(teacherId)).thenReturn(Mono.just(Set.of(MY_CLASS_ID)));
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.empty());

        StepVerifier.create(postService.hideComment(POST_ID, COMMENT_ID, teacherId, false))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verifyNoInteractions(commentRepository);
    }

    /**
     * Moderation of a comment is not a write by a student, so it is not metered: the
     * limiter on {@code addComment} stops comment spam, and this is not that.
     */
    @Test
    void commentModerationDoesNotGoThroughTheCommentRateLimiter() {
        Long teacherId = 7L;
        when(scopeAuthorizer.taughtClassesOf(teacherId)).thenReturn(Mono.just(Set.of(MY_CLASS_ID)));
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(myPost()));
        when(commentRepository.findByIdAndPostIdAndDeletedAtIsNull(COMMENT_ID, POST_ID))
                .thenReturn(Mono.just(myComment(false)));
        when(commentRepository.save(any(PostComment.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        postService.hideComment(POST_ID, COMMENT_ID, teacherId, false).block();

        verify(rateLimiter, never()).check(anyLong());
    }

    /**
     * A post that is already hidden is still a moderation target.
     *
     * <p>The issue's acceptance criteria read "hiding a comment of a hidden post
     * answers 404", but the proposal in the same issue says to reuse
     * {@code requireModerationTarget}, which is what {@code hidePost} does: a
     * moderator who took a post down has to be able to come back and put both it
     * and its comments back. This test pins that reading - the post has to exist
     * and be in the moderator's reach, hidden or not - so the decision is visible
     * here rather than left to be rediscovered as if it were an oversight.</p>
     */
    @Test
    void aHiddenPostIsStillAModerationTarget() {
        Long teacherId = 7L;
        Post hidden = myPost();
        hidden.setIsHidden(true);
        when(scopeAuthorizer.taughtClassesOf(teacherId)).thenReturn(Mono.just(Set.of(MY_CLASS_ID)));
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Mono.just(hidden));
        when(commentRepository.findByIdAndPostIdAndDeletedAtIsNull(COMMENT_ID, POST_ID))
                .thenReturn(Mono.just(myComment(false)));
        when(commentRepository.save(any(PostComment.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(postService.hideComment(POST_ID, COMMENT_ID, teacherId, false)).verifyComplete();

        verify(commentRepository).save(argThat(saved -> Boolean.TRUE.equals(saved.getIsHidden())));
    }
}
