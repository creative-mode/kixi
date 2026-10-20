package ao.creativemode.kixi.feed.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

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
import ao.creativemode.kixi.feed.enums.PostReactionType;
import ao.creativemode.kixi.feed.enums.PostType;
import ao.creativemode.kixi.feed.exception.FeedRateLimitException;
import ao.creativemode.kixi.feed.service.PostService;
import ao.creativemode.kixi.identity.config.CorsConfig;
import ao.creativemode.kixi.identity.config.CorsProperties;
import ao.creativemode.kixi.identity.config.SecurityConfig;
import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.identity.service.JwtService;
import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import ao.creativemode.kixi.shared.service.CurrentAccountService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClientConfigurer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = PostController.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-that-is-at-least-32-characters",
        "app.jwt.expiration-ms=86400000"
})
@Import({SecurityConfig.class, CorsConfig.class, CorsProperties.class,
        CurrentAccountService.class, JwtAuthenticationFilter.class, RequestIdWebFilter.class})
class PostControllerHttpTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private PostService service;

    @Test
    void allFeedOperationsRequireAuthentication() {
        client.get().uri("/api/v1/feed/posts").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/feed/posts").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/feed/posts/1/comments").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/feed/posts/1/reactions").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/feed/posts/1/reports").exchange().expectStatus().isUnauthorized();
        client.patch().uri("/api/v1/feed/posts/1/hide").exchange().expectStatus().isUnauthorized();
        client.patch().uri("/api/v1/feed/posts/1/comments/2/hide").exchange().expectStatus().isUnauthorized();
        client.patch().uri("/api/v1/feed/posts/1/comments/2/unhide").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/feed/posts/reported").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void listsFeedWithServerAuthenticationAndNarrowingParameters() {
        when(service.getFeed(42L, 2026L, 20L, 10L, 2, 25)).thenReturn(Flux.just(post(9L)));
        when(service.getFeedTotalCount(42L, 2026L, 20L, 10L)).thenReturn(Mono.just(31L));

        client.mutateWith(studentJwt()).get().uri(
                        "/api/v1/feed/posts?schoolYearId=2026&courseId=20&classId=10&page=2&limit=25")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$[0].id").isEqualTo(9)
                .jsonPath("$[0].content").isEqualTo("A useful topic");
        client.mutateWith(studentJwt()).get().uri(
                        "/api/v1/feed/posts/count?schoolYearId=2026&courseId=20&classId=10")
                .exchange().expectStatus().isOk().expectBody().json("31");

        verify(service).getFeed(42L, 2026L, 20L, 10L, 2, 25);
        verify(service).getFeedTotalCount(42L, 2026L, 20L, 10L);
    }

    /**
     * Reading the conversation, over HTTP.
     *
     * <p>The account comes from the token and never from the query string: an id in the
     * URL is a post, and whose post it is is the caller's business alone.</p>
     */
    @Test
    void readsTheConversationOfAPost() {
        when(service.listComments(9L, 42L, 1, 20)).thenReturn(Flux.just(
                new PostCommentResponse(3L, 9L, 42L, "A comment", false,
                        LocalDateTime.now(), LocalDateTime.now(), true, "Ana N.")));

        client.mutateWith(studentJwt()).get()
                .uri("http://localhost/api/v1/feed/posts/9/comments")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].content").isEqualTo("A comment")
                .jsonPath("$[0].mine").isEqualTo(true)
                .jsonPath("$[0].displayName").isEqualTo("Ana N.");

        verify(service).listComments(9L, 42L, 1, 20);
    }

    /** A page is the caller's to ask for, and it reaches the service. */
    @Test
    void passesTheRequestedPageAndLimitThrough() {
        when(service.listComments(9L, 42L, 3, 5)).thenReturn(Flux.empty());

        client.mutateWith(studentJwt()).get()
                .uri("http://localhost/api/v1/feed/posts/9/comments?page=3&limit=5")
                .exchange()
                .expectStatus().isOk();

        verify(service).listComments(9L, 42L, 3, 5);
    }

    @Test
    void createsPostAndCoversCommentsReactionsAndReports() {
        PostRequest postRequest = new PostRequest(PostType.TOPIC, 10L, "Title", "A useful topic", null);
        when(service.createPost(42L, postRequest)).thenReturn(Mono.just(post(9L)));
        when(service.addComment(9L, 42L, new PostCommentRequest("A comment")))
                .thenReturn(Mono.just(new PostCommentResponse(3L, 9L, 42L, "A comment", false,
                        LocalDateTime.now(), LocalDateTime.now(), true, "Ana N.")));
        when(service.toggleReaction(9L, 42L, new PostReactionRequest(PostReactionType.APPLAUSE)))
                .thenReturn(Mono.just(new PostReactionToggleResponse(true)));
        when(service.setReaction(9L, 42L, new PostReactionSetRequest(PostReactionType.APPLAUSE, false)))
                .thenReturn(Mono.just(new PostReactionToggleResponse(false)));
        when(service.reportPost(9L, 42L, new PostReportRequest("Not useful")))
                .thenReturn(Mono.just(new PostReportResponse(4L, 9L, 42L, "Not useful", LocalDateTime.now())));

        client.mutateWith(studentJwt()).post().uri("/api/v1/feed/posts")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of(
                        "type", "TOPIC", "classId", 10, "title", "Title", "content", "A useful topic"))
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.id").isEqualTo(9);
        client.mutateWith(studentJwt()).post().uri("/api/v1/feed/posts/9/comments")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("content", "A comment"))
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.postId").isEqualTo(9);
        client.mutateWith(studentJwt()).post().uri("/api/v1/feed/posts/9/reactions")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reaction", "APPLAUSE"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.reacted").isEqualTo(true);
        client.mutateWith(studentJwt()).put().uri("/api/v1/feed/posts/9/reactions")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reaction", "APPLAUSE", "reacted", false))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.reacted").isEqualTo(false);
        client.mutateWith(studentJwt()).post().uri("/api/v1/feed/posts/9/reports")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "Not useful"))
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.id").isEqualTo(4);

        verify(service).createPost(42L, postRequest);
        verify(service).addComment(9L, 42L, new PostCommentRequest("A comment"));
        verify(service).toggleReaction(9L, 42L, new PostReactionRequest(PostReactionType.APPLAUSE));
        verify(service).setReaction(9L, 42L, new PostReactionSetRequest(PostReactionType.APPLAUSE, false));
        verify(service).reportPost(9L, 42L, new PostReportRequest("Not useful"));
    }

    @Test
    void aSimulationPostMayOmitItsNoteButATextPostMayNot() {
        PostRequest card = new PostRequest(PostType.SIMULATION, 10L, null, null, 77L);
        when(service.createPost(42L, card)).thenReturn(Mono.just(post(9L)));

        client.mutateWith(studentJwt()).post().uri("/api/v1/feed/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "SIMULATION", "classId", 10, "simulationId", 77))
                .exchange().expectStatus().isCreated();
        client.mutateWith(studentJwt()).post().uri("/api/v1/feed/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "SIMULATION", "classId", 10, "simulationId", 77, "content", "a".repeat(2001)))
                .exchange().expectStatus().isBadRequest();
        client.mutateWith(studentJwt()).post().uri("/api/v1/feed/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "DOUBT", "classId", 10))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void rejectsInvalidPayloadsBeforeServiceAndPreservesRelevantErrors() {
        client.mutateWith(studentJwt()).post().uri("/api/v1/feed/posts")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("type", "TOPIC", "classId", 10,
                        "content", "no"))
                .exchange().expectStatus().isBadRequest();
        client.mutateWith(studentJwt()).post().uri("/api/v1/feed/posts/9/reactions")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isBadRequest();
        verifyNoInteractions(service);

        when(service.getFeed(42L, null, null, null, 0, 10))
                .thenReturn(Flux.error(ApiException.badRequest("page must be at least 1")));
        client.mutateWith(studentJwt()).get().uri("/api/v1/feed/posts?page=0")
                .exchange().expectStatus().isBadRequest();

        when(service.createPost(eq(42L), any(PostRequest.class)))
                .thenReturn(Mono.error(new FeedRateLimitException(30L)));
        client.mutateWith(studentJwt()).post().uri("/api/v1/feed/posts")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of(
                        "type", "TOPIC", "classId", 10, "content", "valid content"))
                .exchange().expectStatus().isEqualTo(429).expectHeader().valueEquals("Retry-After", "30");
    }

    @Test
    void moderationPassesRoleAndAccountScopeToService() {
        when(service.hidePost(9L, 7L, false)).thenReturn(Mono.empty());
        when(service.unhidePost(9L, 1L, true)).thenReturn(Mono.empty());
        when(service.listReported(7L, false)).thenReturn(Flux.just(
                new ReportedPostResponse(9L, 42L, 10L, "A useful topic", false, 2, LocalDateTime.now())));

        client.mutateWith(teacherJwt()).patch().uri("/api/v1/feed/posts/9/hide")
                .exchange().expectStatus().isNoContent();
        client.mutateWith(adminJwt()).patch().uri("/api/v1/feed/posts/9/unhide")
                .exchange().expectStatus().isNoContent();
        client.mutateWith(teacherJwt()).get().uri("/api/v1/feed/posts/reported")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$[0].reportCount").isEqualTo(2);

        verify(service).hidePost(9L, 7L, false);
        verify(service).unhidePost(9L, 1L, true);
        verify(service).listReported(7L, false);
    }

    /**
     * Moderating a comment reaches the service with the post, the comment, the account
     * and the role, all of them from the URL and the token and none from the body.
     */
    @Test
    void commentModerationPassesRoleAccountAndIdsToService() {
        when(service.hideComment(9L, 3L, 7L, false)).thenReturn(Mono.empty());
        when(service.unhideComment(9L, 3L, 1L, true)).thenReturn(Mono.empty());

        client.mutateWith(teacherJwt()).patch().uri("/api/v1/feed/posts/9/comments/3/hide")
                .exchange().expectStatus().isNoContent();
        client.mutateWith(adminJwt()).patch().uri("/api/v1/feed/posts/9/comments/3/unhide")
                .exchange().expectStatus().isNoContent();

        verify(service).hideComment(9L, 3L, 7L, false);
        verify(service).unhideComment(9L, 3L, 1L, true);
    }

    /**
     * A comment the service refuses answers 404 over HTTP, not 403: the id comes from
     * the URL, and 403 would confirm that it exists.
     *
     * <p>Who decides it is refused is {@code CommentModerationAuthorizationTest},
     * against the real authorizer; here the service is a mock and only the error
     * mapping is under test.</p>
     */
    @Test
    void aCommentRefusedByTheServiceIs404OverHttp() {
        when(service.hideComment(9L, 3L, 7L, false))
                .thenReturn(Mono.error(ApiException.notFound("Comment not found")));

        client.mutateWith(teacherJwt()).patch().uri("/api/v1/feed/posts/9/comments/3/hide")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void studentsCannotUseModeratorRoutes() {
        client.mutateWith(studentJwt()).patch().uri("/api/v1/feed/posts/9/hide")
                .exchange().expectStatus().isForbidden();
        client.mutateWith(studentJwt()).patch().uri("/api/v1/feed/posts/9/comments/3/hide")
                .exchange().expectStatus().isForbidden();
        client.mutateWith(studentJwt()).patch().uri("/api/v1/feed/posts/9/comments/3/unhide")
                .exchange().expectStatus().isForbidden();
        client.mutateWith(studentJwt()).get().uri("/api/v1/feed/posts/reported")
                .exchange().expectStatus().isForbidden();
        verifyNoInteractions(service);
    }

    private static PostResponse post(Long id) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 12, 0);
        return new PostResponse(id, 42L, 1L, 2026L, 20L, 10L, "topic", "Title",
                "A useful topic", false, 0L, false, now, now, null, null);
    }

    private static WebTestClientConfigurer studentJwt() {
        return jwt("42", "ROLE_STUDENT");
    }

    private static WebTestClientConfigurer teacherJwt() {
        return jwt("7", "ROLE_TEACHER");
    }

    private static WebTestClientConfigurer adminJwt() {
        return jwt("1", "ROLE_ADMIN");
    }

    private static WebTestClientConfigurer jwt(String account, String role) {
        return mockAuthentication(new UsernamePasswordAuthenticationToken(account, null,
                List.of(new SimpleGrantedAuthority(role))));
    }
}
