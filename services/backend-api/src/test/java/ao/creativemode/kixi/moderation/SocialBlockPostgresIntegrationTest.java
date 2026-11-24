package ao.creativemode.kixi.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.feed.repository.PostFeedRepository;
import ao.creativemode.kixi.institutions.model.Friendship;
import ao.creativemode.kixi.institutions.repository.FriendshipRepository;
import ao.creativemode.kixi.moderation.service.ModerationService;
import ao.creativemode.kixi.shared.service.FeedScopeAuthorizer.FeedScope;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * O bloqueio contra PostgreSQL real (S5/S6): o SQL do feed, o INSERT idempotente, o bloqueador
 * e a amizade que acaba com o bloqueio. Corre só com KIXI_POSTGRES_TESTS=true.
 */
@SpringBootTest(properties = {
        "spring.r2dbc.pool.max-size=2",
        "spring.r2dbc.pool.initial-size=0",
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
class SocialBlockPostgresIntegrationTest {

    private static final long A = 912001L;
    private static final long B = 912002L;
    private static final long C = 912003L;
    private static final long SCHOOL = 912001L;

    @Autowired private DatabaseClient database;
    @Autowired private PostFeedRepository feed;
    @Autowired private ModerationService moderation;
    @Autowired private FriendshipRepository friendships;

    static {
        if (!"true".equalsIgnoreCase(System.getenv("KIXI_POSTGRES_TESTS"))) {
            throw new IllegalStateException("SocialBlockPostgresIntegrationTest requires KIXI_POSTGRES_TESTS=true and PostgreSQL");
        }
    }

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        String host = env("KIXI_POSTGRES_HOST", "localhost");
        String port = env("KIXI_POSTGRES_PORT", "5433");
        String name = env("KIXI_POSTGRES_DATABASE", "kixi_exam_room_it");
        String user = env("KIXI_POSTGRES_USER", "kixi");
        String password = env("KIXI_POSTGRES_PASSWORD", "kixi_secret");
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + host + ":" + port + "/" + name);
        registry.add("spring.r2dbc.username", () -> user);
        registry.add("spring.r2dbc.password", () -> password);
        registry.add("spring.flyway.url", () -> "jdbc:postgresql://" + host + ":" + port + "/" + name);
        registry.add("spring.flyway.user", () -> user);
        registry.add("spring.flyway.password", () -> password);
    }

    @BeforeEach
    void fixture() {
        clean();
        sql("INSERT INTO accounts (id, username, email, password_hash, email_verified) SELECT i, 'it-912-' || i, 'it-912-' || i || '@example.com', 'x', true "
                + "FROM generate_series(912001, 912003) i");
        for (long author : new long[]{A, B, C}) {
            sql("INSERT INTO posts (author_account_id, institution_id, school_year_id, course_id, class_id, type, content) VALUES ("
                    + author + ", " + SCHOOL + ", 1, 1, 1, 'DOUBT', 'post de " + author + "')");
        }
    }

    @AfterEach
    void clean() {
        sql("DELETE FROM posts WHERE author_account_id BETWEEN 912001 AND 912003");
        sql("DELETE FROM friendships WHERE account_low BETWEEN 912001 AND 912003");
        sql("DELETE FROM moderation_blocks WHERE account_low BETWEEN 912001 AND 912003");
        sql("DELETE FROM accounts WHERE id BETWEEN 912001 AND 912003");
    }

    private static FeedScope scope() {
        return new FeedScope(Set.of(SCHOOL), Set.of(1L), Set.of(1L), Set.of(1L));
    }

    private Set<Long> authorsSeenBy(long viewer) {
        return feed.findFeed(scope(), viewer, null, null, null, 50, 0)
                .map(post -> post.getAuthorAccountId()).collect(java.util.stream.Collectors.toSet())
                .block(Duration.ofSeconds(10));
    }

    @Test
    void aBlockHidesThePostsOfBothSidesAndLeavesTheOthersAlone() {
        assertThat(authorsSeenBy(B)).containsExactlyInAnyOrder(A, B, C);

        moderation.block(A, B).block(Duration.ofSeconds(10));

        assertThat(authorsSeenBy(B)).containsExactlyInAnyOrder(B, C);   // B no longer sees A
        assertThat(authorsSeenBy(A)).containsExactlyInAnyOrder(A, C);   // nor A sees B
        assertThat(authorsSeenBy(C)).containsExactlyInAnyOrder(A, B, C);
        assertThat(feed.countFeed(scope(), B, null, null, null).block(Duration.ofSeconds(10))).isEqualTo(2L);

        moderation.unblock(A, B).block(Duration.ofSeconds(10));
        assertThat(authorsSeenBy(B)).containsExactlyInAnyOrder(A, B, C);
    }

    @Test
    void onlyTheBlockerSeesAndLiftsTheBlockAndBlockingTwiceIsIdempotent() {
        moderation.block(B, A).block(Duration.ofSeconds(10));
        moderation.block(B, A).block(Duration.ofSeconds(10));   // second time: no error, same blocker
        moderation.block(A, B).block(Duration.ofSeconds(10));   // the other side cannot take it over

        assertThat(moderation.blockedByViewer(B, A).block(Duration.ofSeconds(10))).isTrue();
        assertThat(moderation.blockedByViewer(A, B).block(Duration.ofSeconds(10))).isFalse();
        assertThat(moderation.listBlocked(B).collectList().block(Duration.ofSeconds(10)))
                .extracting(entry -> entry.accountId()).containsExactly(A);
        assertThat(moderation.listBlocked(A).collectList().block(Duration.ofSeconds(10))).isEmpty();
        assertThat(moderation.blockState(A, B).block(Duration.ofSeconds(10)).blockedByMe()).isFalse();

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> moderation.unblock(A, B).block(Duration.ofSeconds(10)));
        assertThat(moderation.blockedBetween(A, B).block(Duration.ofSeconds(10))).isTrue();

        moderation.unblock(B, A).block(Duration.ofSeconds(10));
        assertThat(moderation.blockedBetween(A, B).block(Duration.ofSeconds(10))).isFalse();
    }

    @Test
    void blockingAFriendEndsTheFriendship() {
        Friendship friendship = Friendship.between(A, B, A);
        friendship.accept();
        friendships.save(friendship).block(Duration.ofSeconds(10));
        assertThat(friendships.findAcceptedBy(A).collectList().block(Duration.ofSeconds(10))).hasSize(1);

        moderation.block(A, B).block(Duration.ofSeconds(10));

        assertThat(friendships.findAcceptedBy(A).collectList().block(Duration.ofSeconds(10))).isEmpty();
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(Duration.ofSeconds(10));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
