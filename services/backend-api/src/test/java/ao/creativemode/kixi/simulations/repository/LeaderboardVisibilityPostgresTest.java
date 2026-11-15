package ao.creativemode.kixi.simulations.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ao.creativemode.kixi.simulations.repository.LeaderboardRepository.AccountVisibility;
import ao.creativemode.kixi.simulations.repository.LeaderboardRepository.Scope;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The per-scope visibility table against a migrated database (issue #611).
 *
 * <p>The service tests mock the repository, so this is the only place that proves the
 * migration, the unique constraint and the upsert agree. The upsert is the part that
 * matters: two toggles of the same switch must leave one answer, not two rows that the
 * ranking would have to choose between.</p>
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

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final long FIRST = 911001L;
    private static final long SECOND = 911002L;

    @Autowired LeaderboardRepository repository;
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
    void seed() {
        clean();
        for (long id : new long[] {FIRST, SECOND}) {
            sql("INSERT INTO accounts (id, username, email, password_hash, email_verified,"
                    + " active, created_at, updated_at)"
                    + " VALUES (" + id + ", 'vis-" + id + "', 'vis-" + id + "@example.com',"
                    + " 'x', true, true, now(), now())");
        }
    }

    @AfterEach
    void leaveNothingBehind() {
        clean();
    }

    private void clean() {
        // A block of its own, away from the 940000/950000/960000 ranges the other
        // Postgres suites use. Sharing one put a simulation from the room suite in the
        // way of this delete: its foreign key points at an account this test had not
        // written, and the cleanup failed before any assertion ran.
        sql("DELETE FROM leaderboard_visibility WHERE account_id BETWEEN 911000 AND 911999");
        sql("DELETE FROM simulations WHERE account_id BETWEEN 911000 AND 911999");
        sql("DELETE FROM users WHERE account_id BETWEEN 911000 AND 911999");
        sql("DELETE FROM enrollments WHERE account_id BETWEEN 911000 AND 911999");
        sql("DELETE FROM account_roles WHERE account_id BETWEEN 911000 AND 911999");
        sql("DELETE FROM friendships WHERE account_low BETWEEN 911000 AND 911999"
                + " OR account_high BETWEEN 911000 AND 911999");
        sql("DELETE FROM user_stats WHERE account_id BETWEEN 911000 AND 911999");
        sql("DELETE FROM accounts WHERE id BETWEEN 911000 AND 911999");
    }

    @Test
    void aVisibilityIsStoredOncePerAccountAndScope() {
        assertThat(repository.setVisibility(FIRST, Scope.CLASS, "ANONYMOUS").block(WAIT)).isTrue();
        assertThat(repository.setVisibility(FIRST, Scope.CLASS, "OUT").block(WAIT)).isTrue();

        List<AccountVisibility> rows = repository.findVisibilities(FIRST)
                .collectList().block(WAIT);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.scope()).isEqualTo("CLASS");
            assertThat(row.rankingVisibility()).isEqualTo("OUT");
        });
    }

    @Test
    void eachScopeHasItsOwnAnswer() {
        repository.setVisibility(FIRST, Scope.CLASS, "OUT").block(WAIT);
        repository.setVisibility(FIRST, Scope.SCHOOL, "VISIBLE").block(WAIT);

        assertThat(repository.findVisibilities(FIRST).collectList().block(WAIT))
                .extracting(AccountVisibility::scope)
                .containsExactlyInAnyOrder("CLASS", "SCHOOL");
        assertThat(repository.findVisibilities(List.of(FIRST, SECOND), Scope.CLASS)
                .collectList().block(WAIT))
                .singleElement()
                .satisfies(row -> assertThat(row.accountId()).isEqualTo(FIRST));
    }

    @Test
    void globalVisibilityIsRefusedByTheRepositoryToo() {
        assertThatThrownBy(() -> repository.setVisibility(FIRST, Scope.GLOBAL, "OUT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consent");
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(WAIT);
    }
}
