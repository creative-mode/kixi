package ao.creativemode.kixi.simulations.repository;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.simulations.repository.LeaderboardRepository.GroupScore;
import ao.creativemode.kixi.simulations.repository.LeaderboardRepository.Scope;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code scope=friends} against a database migrated by Flyway (issue #121).
 *
 * <p>The friendship ranking is the first cohort that is not a column on
 * {@code enrollments}: it is a pair of accounts on another table, and both the rows and
 * the count that decides the podium come from it. Mocking the repository cannot tell
 * whether those two agree — and if they did not, {@code totalStudents} would decide the
 * small-group floor from a number that is not the number of rows behind it.</p>
 *
 * <p>Ids from 940000: the suite next door keeps 920000 (analytics) and 930000
 * (tutor styles), and all three clean up the rows they seed.</p>
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
class LeaderboardFriendsPostgresTest {

    private static final long ANA = 940001L;
    private static final long BRUNO = 940002L;
    private static final long CARLA = 940003L;
    private static final long DANI = 940004L;
    private static final long CLASS = 940001L;
    private static final long STATEMENT = 940001L;
    private static final long YEAR = 940001L;

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
    void seed() {
        exec("DELETE FROM simulation_answers WHERE simulation_id BETWEEN 940000 AND 949999");
        exec("DELETE FROM simulations WHERE id BETWEEN 940000 AND 949999");
        exec("DELETE FROM statements WHERE id BETWEEN 940000 AND 949999");
        exec("DELETE FROM friendships WHERE account_low BETWEEN 940000 AND 949999"
                + " OR account_high BETWEEN 940000 AND 949999");
        exec("DELETE FROM enrollments WHERE account_id BETWEEN 940000 AND 949999");
        exec("DELETE FROM account_roles WHERE account_id BETWEEN 940000 AND 949999");
        // users before accounts: users.account_id is a foreign key, and deleting the
        // account first fails on it.
        exec("DELETE FROM users WHERE id BETWEEN 940000 AND 949999");
        exec("DELETE FROM accounts WHERE id BETWEEN 940000 AND 949999");
        exec("DELETE FROM statements WHERE class_id BETWEEN 940000 AND 949999");
        exec("DELETE FROM classes WHERE id BETWEEN 940000 AND 949999");
        exec("DELETE FROM courses WHERE id BETWEEN 940000 AND 949999");
        exec("DELETE FROM institutions WHERE id BETWEEN 940000 AND 949999");
        // By the range, not the id: a run that dies half way leaves the row behind,
        // and the next one has to start clean whatever id it was written with.
        exec("DELETE FROM school_years WHERE start_year = 2030 AND end_year = 2031");

        // The chain an enrollment hangs from. Without it the row is refused before the
        // test ever reaches the query it is written to check.
        exec("INSERT INTO institutions (id, code, name, created_at, updated_at)"
                + " VALUES (" + CLASS + ", 'ESC" + CLASS + "', 'Escola', now(), now())");
        exec("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at)"
                + " VALUES (" + YEAR + ", 2030, 2031, now(), now())");
        exec("INSERT INTO courses (id, code, name, institution_id)"
                + " VALUES (" + CLASS + ", 'C" + CLASS + "', 'Curso', " + CLASS + ")");
        exec("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id,"
                + " created_at, updated_at)"
                + " VALUES (" + CLASS + ", 'T" + CLASS + "', 7, " + CLASS + ", " + YEAR + ", "
                + CLASS + ", now(), now())");

        for (long account : List.of(ANA, BRUNO, CARLA, DANI)) {
            exec("INSERT INTO accounts (id, username, email, password_hash, email_verified,"
                    + " active, created_at, updated_at, accessibility_extra_time)"
                    + " VALUES (" + account + ", 'u" + account + "', 'u" + account + "@x.pt',"
                    + " 'x', true, true, now(), now(), false)");
            exec("INSERT INTO users (id, account_id, first_name, last_name, created_at, updated_at)"
                    + " VALUES (" + account + ", " + account + ", 'Aluno" + account + "',"
                    + " 'Teste', now(), now())");
            exec("INSERT INTO enrollments (id, account_id, class_id, school_year_id, status,"
                    + " created_at, updated_at)"
                    + " VALUES (" + account + ", " + account + ", " + CLASS + ", " + YEAR + ","
                    + " 'ACTIVE', now(), now())");
        }

        exec("INSERT INTO statements (id, title, class_id, total_max_score, visible, needs_review,"
                + " exam_type, duration_minutes, school_year_id, created_at, updated_at)"
                + " VALUES (" + STATEMENT + ", 'Prova', " + CLASS + ", 10, true, false,"
                + " 'TEST', 60, " + YEAR + ", now(), now())");

        // Ana 8/10 = 80%, Bruno 4/10 = 40%, Carla 6/10 = 60%. Dani sat nothing.
        finished(940001L, ANA, 8);
        finished(940002L, BRUNO, 4);
        finished(940003L, CARLA, 6);

        // Ana and Bruno are friends. The pair is stored the same way round for both.
        friendship(940001L, ANA, BRUNO, BRUNO, "ACCEPTED");
        // Carla asked Ana and was never answered: not a friend, so not ranked.
        friendship(940002L, ANA, CARLA, CARLA, "PENDING");
        // Dani and Ana are friends but Dani has no score to rank.
        friendship(940003L, ANA, DANI, DANI, "ACCEPTED");
    }

    // ── The cohort is the friendships, not the class ─────────────────────

    @Test
    void aCallerRanksTheFriendsAndNobodyElse() {
        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.FRIENDS, ANA, YEAR, null, null, null)
                .collectList()
                .block();

        // Bruno has a score. Carla asked and was never answered. Dani is a friend but
        // sat nothing — a person with no mark has no average, so there is no row for
        // them, which is the same rule a class follows and not a gap in the cohort.
        assertThat(ranked).extracting(GroupScore::accountId).containsExactly(BRUNO);
    }

    /** The caller's own row is not their own friend. */
    @Test
    void theCallerIsNotInTheirOwnRanking() {
        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.FRIENDS, ANA, YEAR, null, null, null)
                .collectList()
                .block();

        assertThat(ranked).extracting(GroupScore::accountId).doesNotContain(ANA);
    }

    /** Percentages, not raw marks: 8/10 and 4/10 are not 8 and 4. */
    @Test
    void theAverageIsAPercentageOfTheStatementMaximum() {
        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.FRIENDS, ANA, YEAR, null, null, null)
                .collectList()
                .block();

        assertThat(ranked).filteredOn(score -> score.accountId().equals(BRUNO))
                .singleElement()
                .satisfies(score -> assertThat(score.average()).isEqualTo(40.0));
    }

    /**
     * The friendship is undirected: both ends rank each other.
     *
     * <p>This is the half that a pair stored in the wrong order would break, and the
     * {@code CHECK} on the migration is what makes the wrong order unwritable.</p>
     */
    @Test
    void theFriendshipWorksFromBothEnds() {
        List<GroupScore> fromBruno = leaderboard
                .findAverages(Scope.FRIENDS, BRUNO, YEAR, null, null, null)
                .collectList()
                .block();

        assertThat(fromBruno).extracting(GroupScore::accountId).containsExactly(ANA);
    }

    /** A request nobody answered ranks nobody. */
    @Test
    void aPendingRequestIsNotAFriendYet() {
        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.FRIENDS, ANA, YEAR, null, null, null)
                .collectList()
                .block();

        assertThat(ranked).extracting(GroupScore::accountId).doesNotContain(CARLA);
    }

    // ── The count that decides the podium ───────────────────────────────

    /**
     * The count and the rows are read from the same table, under the same conditions.
     *
     * <p>Two accepted friends, one of them without a score: {@code totalStudents} counts
     * both, because the count is about who is in the group, and {@code rankedStudents}
     * is the smaller number. A count that followed only the scored rows would let a
     * group of two look like a group of one and open a podium nobody is standing in.</p>
     */
    @Test
    void theCountAndTheRowsAgreeOnWhoIsInTheGroup() {
        Long total = leaderboard.countMembers(Scope.FRIENDS, ANA, YEAR).block();
        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.FRIENDS, ANA, YEAR, null, null, null)
                .collectList()
                .block();

        assertThat(total).isEqualTo(2L);
        assertThat(ranked).hasSize(1);
    }

    /** A caller with no friends has nobody, and not everybody. */
    @Test
    void aCallerWithNoFriendsRanksNobody() {
        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.FRIENDS, 999999L, YEAR, null, null, null)
                .collectList()
                .block();

        assertThat(ranked).isEmpty();
        assertThat(leaderboard.countMembers(Scope.FRIENDS, 999999L, YEAR).block()).isZero();
    }

    /**
     * The year still bounds the cohort.
     *
     * <p>A mark moved out of the year leaves the ranking: the same rule as a class, and
     * worth proving here because the friendship itself does not mention a year at all —
     * only the simulation does.</p>
     */
    @Test
    void aMarkFromAnotherYearIsNotRanked() {
        // School year 1 is the one the migrations seed. The column is a foreign key,
        // so a mark can only be moved into a year that exists, and inserting a second
        // 2024-2025 would break its own unique constraint.
        exec("UPDATE simulations SET school_year_id = 1 WHERE id = 940002");

        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.FRIENDS, ANA, YEAR, null, null, null)
                .collectList()
                .block();

        assertThat(ranked).isEmpty();
    }

    /** A deactivated account stops being rankable, as it does in a class. */
    @Test
    void aDeactivatedFriendDropsOut() {
        exec("UPDATE accounts SET active = false WHERE id = " + DANI);

        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.FRIENDS, ANA, YEAR, null, null, null)
                .collectList()
                .block();

        assertThat(ranked).extracting(GroupScore::accountId).containsExactly(BRUNO);
        assertThat(leaderboard.countMembers(Scope.FRIENDS, ANA, YEAR).block()).isEqualTo(1L);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void finished(long id, long account, int score) {
        exec("INSERT INTO simulations (id, account_id, statement_id, school_year_id, status,"
                + " final_score, time_spent_seconds, started_at, finished_at, created_at, updated_at)"
                + " VALUES (" + id + ", " + account + ", " + STATEMENT + ", " + YEAR + ","
                + " 'FINISHED', " + score + ", 600, now(), now(), now(), now())");
    }

    private void friendship(long id, long low, long high, long requester, String status) {
        exec("INSERT INTO friendships (id, account_low, account_high, requested_by, status,"
                + " created_at, updated_at)"
                + " VALUES (" + id + ", " + low + ", " + high + ", " + requester + ", '"
                + status + "', now(), now())");
    }

    private void exec(String sql) {
        database.sql(sql).fetch().rowsUpdated().block();
    }
}