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
    private static final long INSTITUTION = 940001L;
    private static final long OTHER_CLASS = 940002L;
    private static final long OTHER_INSTITUTION = 940002L;

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
        exec("DELETE FROM user_stats WHERE account_id BETWEEN 940000 AND 949999");
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
        exec("DELETE FROM school_years WHERE id = 940001"
                + " OR (start_year = 2030 AND end_year = 2031)");

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

        // Another school in the same school year. school_years is global — one row per
        // calendar span — so the year alone cannot tell the two apart, which is the whole
        // point of the next two tests.
        exec("INSERT INTO institutions (id, code, name, created_at, updated_at)"
                + " VALUES (" + OTHER_CLASS + ", 'ESC" + OTHER_CLASS + "', 'Escola B', now(), now())");
        exec("INSERT INTO courses (id, code, name, institution_id)"
                + " VALUES (" + OTHER_CLASS + ", 'C" + OTHER_CLASS + "', 'Curso B', " + OTHER_CLASS + ")");
        exec("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id,"
                + " created_at, updated_at)"
                + " VALUES (" + OTHER_CLASS + ", 'TB', 7, " + OTHER_CLASS + ", " + YEAR + ", "
                + OTHER_CLASS + ", now(), now())");

        // Everyone but Dani, who is seated below: the loop gives every account an
        // enrollment, and Dani needs one in the other school instead.
        for (long account : List.of(ANA, BRUNO, CARLA)) {
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

        // Dani moved schools: the old enrollment is CANCELLED, not deleted, which is the
        // state it is really in. The unique index allows one ACTIVE enrollment per
        // account and year, so this is how a second school ever appears at all.
        exec("INSERT INTO accounts (id, username, email, password_hash, email_verified,"
                + " active, created_at, updated_at, accessibility_extra_time)"
                + " VALUES (" + DANI + ", 'u" + DANI + "', 'u" + DANI + "@x.pt',"
                + " 'x', true, true, now(), now(), false)");
        exec("INSERT INTO users (id, account_id, first_name, last_name, created_at, updated_at)"
                + " VALUES (" + DANI + ", " + DANI + ", 'Aluno" + DANI + "',"
                + " 'Teste', now(), now())");
        exec("INSERT INTO enrollments (id, account_id, class_id, school_year_id, status,"
                + " created_at, updated_at)"
                + " VALUES (940005, " + DANI + ", " + OTHER_CLASS + ", " + YEAR + ","
                + " 'ACTIVE', now(), now())");

        // Lifetime XP, which only metric=xp orders by. Bruno has more than Ana despite
        // scoring half as much, so the two metrics cannot be the same table.
        exec("INSERT INTO user_stats (account_id, xp, level, streak, longest_streak,"
                + " free_pauses, created_at, updated_at)"
                + " VALUES (" + ANA + ", 50, 1, 0, 0, 1, now(), now())"
                + " ON CONFLICT (account_id) DO UPDATE SET xp = 50");
        exec("INSERT INTO user_stats (account_id, xp, level, streak, longest_streak,"
                + " free_pauses, created_at, updated_at)"
                + " VALUES (" + BRUNO + ", 900, 3, 0, 0, 1, now(), now())"
                + " ON CONFLICT (account_id) DO UPDATE SET xp = 900");
        // Carla has no user_stats row at all, which is the LEFT JOIN case.

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
                .findAverages(Scope.FRIENDS, ANA, YEAR, INSTITUTION, null, null, null)
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
                .findAverages(Scope.FRIENDS, ANA, YEAR, INSTITUTION, null, null, null)
                .collectList()
                .block();

        assertThat(ranked).extracting(GroupScore::accountId).doesNotContain(ANA);
    }

    /** Percentages, not raw marks: 8/10 and 4/10 are not 8 and 4. */
    @Test
    void theAverageIsAPercentageOfTheStatementMaximum() {
        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.FRIENDS, ANA, YEAR, INSTITUTION, null, null, null)
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
                .findAverages(Scope.FRIENDS, BRUNO, YEAR, INSTITUTION, null, null, null)
                .collectList()
                .block();

        assertThat(fromBruno).extracting(GroupScore::accountId).containsExactly(ANA);
    }

    /** A request nobody answered ranks nobody. */
    @Test
    void aPendingRequestIsNotAFriendYet() {
        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.FRIENDS, ANA, YEAR, INSTITUTION, null, null, null)
                .collectList()
                .block();

        assertThat(ranked).extracting(GroupScore::accountId).doesNotContain(CARLA);
    }

    // ── metric=xp ────────────────────────────────────────────────────────

    /**
     * The XP comes back with the row, and only the XP is missing when it is absent.
     *
     * <p>An inner join instead of a left one would drop every account with no
     * {@code user_stats} row — which is every student who has not finished a simulation
     * since the gamification landed — and the ranking would quietly be of the rewarded.</p>
     */
    @Test
    void theXpTravelsWithTheRowAndItsAbsenceDoesNotDropTheStudent() {
        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.FRIENDS, ANA, YEAR, INSTITUTION, null, null, null)
                .collectList()
                .block();

        assertThat(ranked).extracting(GroupScore::accountId).containsExactly(BRUNO);
        assertThat(ranked).singleElement()
                .satisfies(score -> assertThat(score.xp()).isEqualTo(900L));
    }

    /** The row a join would have thrown away is still there, with a null XP. */
    @Test
    void aStudentWithNoStatsRowIsStillRanked() {
        // A fourth account in Ana's own school, accepted as a friend and with no
        // user_stats row: the one thing a LEFT JOIN exists to keep.
        exec("INSERT INTO accounts (id, username, email, password_hash, email_verified,"
                + " active, created_at, updated_at, accessibility_extra_time)"
                + " VALUES (940007, 'u940007', 'u940007@x.pt', 'x', true, true, now(), now(), false)");
        exec("INSERT INTO users (id, account_id, first_name, last_name, created_at, updated_at)"
                + " VALUES (940007, 940007, 'Rui', 'Pires', now(), now())");
        exec("INSERT INTO enrollments (id, account_id, class_id, school_year_id, status,"
                + " created_at, updated_at)"
                + " VALUES (940008, 940007, " + CLASS + ", " + YEAR + ", 'ACTIVE', now(), now())");
        friendship(940004L, ANA, 940007L, 940007L, "ACCEPTED");
        finished(940006L, 940007L, 7);

        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.FRIENDS, ANA, YEAR, INSTITUTION, null, null, null)
                .collectList()
                .block();

        assertThat(ranked).extracting(GroupScore::accountId).contains(940007L);
        assertThat(ranked).filteredOn(score -> score.accountId().equals(940007L))
                .singleElement()
                .satisfies(score -> assertThat(score.xp()).isNull());
    }

    // ── School isolation ─────────────────────────────────────────────────

    /**
     * A friend who moved to another school leaves the ranking.
     *
     * <p>The friendship row is perfectly valid: {@code ACCEPTED}, and both accounts are
     * active, so every check the friendship itself can make passes. What it cannot make
     * is the school — and {@code school_years} is one global row per calendar span that
     * every school shares, so the year filter never separated them. Without the
     * institution check this school would go on publishing a former student's marks.</p>
     */
    @Test
    void aFriendWhoMovedSchoolsIsNotRankedHere() {
        Long total = leaderboard.countMembers(Scope.FRIENDS, ANA, YEAR, INSTITUTION).block();

        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.FRIENDS, ANA, YEAR, INSTITUTION, null, null, null)
                .collectList()
                .block();

        // Bruno is here and sat the paper. Dani is a friend and moved schools: the row
        // is accepted and the account is active, and neither is enough.
        assertThat(total).isEqualTo(1L);
        assertThat(ranked).extracting(GroupScore::accountId).containsExactly(BRUNO);
    }

    /** Asked against the school they moved to, the same person is exactly who they are. */
    @Test
    void theSameFriendIsRankedInTheSchoolTheyMovedTo() {
        Long total = leaderboard.countMembers(Scope.FRIENDS, ANA, YEAR, OTHER_INSTITUTION).block();

        assertThat(total).isEqualTo(1L);
    }

    /** A seat in no school has nothing to isolate by, so the cohort is empty. */
    @Test
    void aCallerInNoSchoolRanksNoFriends() {
        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.FRIENDS, ANA, YEAR, null, null, null, null)
                .collectList()
                .block();

        assertThat(ranked).isEmpty();
        assertThat(leaderboard.countMembers(Scope.FRIENDS, ANA, YEAR, null).block()).isZero();
    }

    // ── The count that decides the podium ───────────────────────────────

    /**
     * The count and the rows are read from the same table, under the same conditions.
     *
     * <p>One accepted friend, and they sat nothing: {@code totalStudents} counts them,
     * because the count is about who is in the group, and {@code rankedStudents} is the
     * smaller number. A count that followed only the scored rows would let a group of
     * two look like a group of one and open a podium nobody is standing in.</p>
     */
    @Test
    void theCountAndTheRowsAgreeOnWhoIsInTheGroup() {
        Long total = leaderboard.countMembers(Scope.FRIENDS, ANA, YEAR, INSTITUTION).block();
        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.FRIENDS, ANA, YEAR, INSTITUTION, null, null, null)
                .collectList()
                .block();

        // Ana is a friend of Bruno, who sat the paper, and of Dani, who did not and is
        // at the other school. One is in the group; one has a mark.
        assertThat(total).isEqualTo(1L);
        assertThat(ranked).hasSize(1);
    }

    /** A caller with no friends has nobody, and not everybody. */
    @Test
    void aCallerWithNoFriendsRanksNobody() {
        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.FRIENDS, 999999L, YEAR, INSTITUTION, null, null, null)
                .collectList()
                .block();

        assertThat(ranked).isEmpty();
        assertThat(leaderboard.countMembers(Scope.FRIENDS, 999999L, YEAR, INSTITUTION).block()).isZero();
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
                .findAverages(Scope.FRIENDS, ANA, YEAR, INSTITUTION, null, null, null)
                .collectList()
                .block();

        assertThat(ranked).isEmpty();
    }

    /** A deactivated account stops being rankable, as it does in a class. */
    @Test
    void aDeactivatedFriendDropsOut() {
        exec("UPDATE accounts SET active = false WHERE id = " + BRUNO);

        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.FRIENDS, ANA, YEAR, INSTITUTION, null, null, null)
                .collectList()
                .block();

        assertThat(ranked).isEmpty();
        assertThat(leaderboard.countMembers(Scope.FRIENDS, ANA, YEAR, INSTITUTION).block()).isZero();
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