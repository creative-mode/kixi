package ao.creativemode.kixi.simulations.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import reactor.core.publisher.Mono;
import ao.creativemode.kixi.simulations.repository.LeaderboardRepository.Scope;

/**
 * The preference table behind {@code PUT /api/v1/leaderboard/visibility} (issue #611).
 *
 * <p>Visibility is the choice a student makes about themselves, and this is the only
 * place that choice is written. The two things a mock cannot check are the two things
 * that make a "hidden" row mean what the caller asked for: that {@code (account, scope)}
 * really is unique so a repeated click updates rather than duplicates, and that the
 * database refuses a third answer that no client knows about.</p>
 *
 * <p>Ids from 960000, outside the ranges kept by the other ranking suites, cleaned up
 * by the range.</p>
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
class LeaderboardVisibilityPostgresTest {

    private static final long ACCOUNT = 960001L;

    @Autowired LeaderboardRepository leaderboard;
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

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    @BeforeEach
    void clean() {
        database.sql("DELETE FROM leaderboard_visibility WHERE account_id BETWEEN 960000 AND 969999")
                .fetch().rowsUpdated().block();
    }

    @Test
    void aRepeatedChoiceUpdatesTheRowInsteadOfLeavingTwo() {
        leaderboard.setVisibility(ACCOUNT, Scope.CLASS, "anonymous").block();
        leaderboard.setVisibility(ACCOUNT, Scope.CLASS, "hidden").block();

        long rows = database.sql(
                        "SELECT COUNT(*) AS n FROM leaderboard_visibility"
                                + " WHERE account_id = :accountId AND scope = 'class'")
                .bind("accountId", ACCOUNT)
                .map((row, metadata) -> ((Number) row.get("n")).longValue())
                .one().block();

        assertThat(rows).isEqualTo(1);
        assertThat(leaderboard.findVisibility(ACCOUNT, Scope.CLASS).block()).isEqualTo("hidden");
    }

    @Test
    void absentRowMeansVisible() {
        // Nobody asked this account about anything, so it never opted in and never
        // opted out: absent is visible (#611).
        assertThat(leaderboard.findVisibility(ACCOUNT, Scope.SCHOOL).block()).isEqualTo("visible");
    }

    @Test
    void anAnswerOutsideTheThreeIsRefusedByTheDatabase() {
        // Direct write, as a client could never ask for it: the CHECK refuses it before
        // it could reach a row, so a future endpoint cannot invent a fourth answer.
        assertThatThrownBy(() -> database.sql(
                        "INSERT INTO leaderboard_visibility (account_id, scope, preference)"
                                + " VALUES (:accountId, 'class', 'sometimes')")
                        .bind("accountId", ACCOUNT)
                        .fetch().rowsUpdated().block())
                .isNotNull();
    }
}
