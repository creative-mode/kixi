package ao.creativemode.kixi.institutions.service;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.institutions.model.Friendship;
import ao.creativemode.kixi.institutions.repository.FriendshipRepository;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The friend-request inbox against a migrated database.
 *
 * <p>A friendship is stored low/high, not requester/addressee, so "addressed to me" is "I am
 * one end of the pair and I did not send it". The inbox used to ask only for
 * {@code account_high = me}: a request sent by the higher account to the lower one reached
 * nobody, and the lower account saw an empty inbox.</p>
 *
 * <p>Accounts 981001, 981002 and 981003.</p>
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "app.simulations.expiry-poll-ms=86400000",
        "app.simulations.expiry-enabled=false",
        "app.jwt.secret=integration-test-secret-which-is-long-enough",
        "ocr.service.url=http://127.0.0.1:1",
        "ocr.service.api-key=test"
})
@EnabledIfEnvironmentVariable(named = "KIXI_POSTGRES_TESTS", matches = "true")
class FriendshipInboxPostgresTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final long LOW = 981001L;
    private static final long MIDDLE = 981002L;
    private static final long HIGH = 981003L;

    @Autowired FriendshipRepository friendships;
    @Autowired DatabaseClient database;

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
    void accounts() {
        sql("DELETE FROM friendships WHERE account_low IN (" + LOW + "," + MIDDLE + "," + HIGH + ") "
                + "OR account_high IN (" + LOW + "," + MIDDLE + "," + HIGH + ")");
        sql("DELETE FROM accounts WHERE id IN (" + LOW + "," + MIDDLE + "," + HIGH + ")");
        for (long id : new long[] {LOW, MIDDLE, HIGH}) {
            sql("INSERT INTO accounts (id, username, email, password_hash, email_verified, active, created_at, updated_at) "
                    + "VALUES (" + id + ", 'inbox-" + id + "', 'inbox-" + id + "@example.com', 'x', true, true, now(), now())");
        }
    }

    @Test
    void aRequestFromTheHigherAccountReachesTheLowerOnesInbox() {
        friendships.save(Friendship.between(LOW, HIGH, HIGH)).block(WAIT);

        assertThat(friendships.findPendingFor(LOW).collectList().block(WAIT)).hasSize(1);
    }

    @Test
    void aRequestFromTheLowerAccountReachesTheHigherOnesInbox() {
        friendships.save(Friendship.between(LOW, HIGH, LOW)).block(WAIT);

        assertThat(friendships.findPendingFor(HIGH).collectList().block(WAIT)).hasSize(1);
    }

    @Test
    void theSenderDoesNotSeeTheirOwnRequestInTheInboxWhicheverEndTheyAre() {
        friendships.save(Friendship.between(LOW, MIDDLE, LOW)).block(WAIT);
        friendships.save(Friendship.between(MIDDLE, HIGH, HIGH)).block(WAIT);

        assertThat(friendships.findPendingFor(LOW).collectList().block(WAIT)).isEmpty();
        assertThat(friendships.findPendingFor(HIGH).collectList().block(WAIT)).isEmpty();
        assertThat(friendships.findPendingFor(MIDDLE).collectList().block(WAIT)).hasSize(2);
    }

    @Test
    void anAnsweredRequestLeavesTheInbox() {
        Friendship accepted = Friendship.between(LOW, HIGH, HIGH);
        accepted.setStatus(Friendship.ACCEPTED);
        friendships.save(accepted).block(WAIT);

        assertThat(friendships.findPendingFor(LOW).collectList().block(WAIT)).isEmpty();
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(WAIT);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
