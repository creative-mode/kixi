package ao.creativemode.kixi.simulations.repository;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.simulations.repository.LeaderboardRepository.GroupScore;
import ao.creativemode.kixi.simulations.repository.LeaderboardRepository.Scope;

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
 * {@code scope=global} against a database migrated by Flyway (issue #189, ADR-0013).
 *
 * <p>This cohort is the first one built out of CTEs rather than one {@code EXISTS}, and
 * the two things a mock cannot check are the whole point of it: that a window function
 * computes a percentile <em>inside</em> the student's own school, and that the count
 * {@code totalStudents} decides the small-group floor from is asking the same question as
 * the rows behind it. Both were wrong at least once while this was being written — the
 * count came out one higher than the rows, because only one of the two had the consent
 * gate on it.</p>
 *
 * <p>Ids from 950000: the friends ranking next door keeps 940000 and the analytics and
 * tutor suites keep 920000/930000, and all four clean up the rows they seed.</p>
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
class LeaderboardGlobalPostgresTest {

    private static final long YEAR = 950001L;
    private static final long STATEMENT = 950001L;

    /** Two schools that make the threshold, and one that does not. */
    private static final long SCHOOL_A = 950001L;
    private static final long CLASS_A = 950001L;
    private static final long FIRST_A = 950101L;
    private static final long SCHOOL_B = 950002L;
    private static final long CLASS_B = 950002L;
    private static final long FIRST_B = 950201L;
    private static final long SMALL_SCHOOL = 950003L;
    private static final long SMALL_CLASS = 950003L;
    private static final long FIRST_SMALL = 950301L;

    /** The two students who exercise the consent gate. */
    private static final long NO_CONSENT_ROW = 950401L;
    private static final long CONSENT_REVOKED = 950402L;

    private static final int TEN = 10;

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
        exec("DELETE FROM simulation_answers WHERE simulation_id BETWEEN 950000 AND 959999");
        exec("DELETE FROM simulations WHERE id BETWEEN 950000 AND 959999");
        exec("DELETE FROM statements WHERE id BETWEEN 950000 AND 959999");
        exec("DELETE FROM enrollments WHERE account_id BETWEEN 950000 AND 959999");
        exec("DELETE FROM leaderboard_consents WHERE account_id BETWEEN 950000 AND 959999");
        exec("DELETE FROM account_roles WHERE account_id BETWEEN 950000 AND 959999");
        // users before accounts: users.account_id is a foreign key, and deleting the
        // account first fails on it.
        exec("DELETE FROM users WHERE id BETWEEN 950000 AND 959999");
        exec("DELETE FROM accounts WHERE id BETWEEN 950000 AND 959999");
        exec("DELETE FROM statements WHERE class_id BETWEEN 950000 AND 959999");
        exec("DELETE FROM classes WHERE id BETWEEN 950000 AND 959999");
        exec("DELETE FROM courses WHERE id BETWEEN 950000 AND 959999");
        exec("DELETE FROM institutions WHERE id BETWEEN 950000 AND 959999");
        // By the range, not the id: a run that dies half way leaves the row behind,
        // and the next one has to start clean whatever id it was written with.
        exec("DELETE FROM school_years WHERE id = " + YEAR
                + " OR (start_year = 2035 AND end_year = 2036)");

        exec("INSERT INTO school_years (id, start_year, end_year, created_at, updated_at)"
                + " VALUES (" + YEAR + ", 2035, 2036, now(), now())");

        school(SCHOOL_A, CLASS_A, "A");
        school(SCHOOL_B, CLASS_B, "B");
        school(SMALL_SCHOOL, SMALL_CLASS, "Pequena");

        // One statement shared by everybody: what is being compared is the percentage, so
        // the scale has to be the same for the comparison to be about the students and
        // not about the papers.
        exec("INSERT INTO statements (id, title, class_id, total_max_score, visible, needs_review,"
                + " exam_type, duration_minutes, school_year_id, created_at, updated_at)"
                + " VALUES (" + STATEMENT + ", 'Prova', " + CLASS_A + ", 10, true, false,"
                + " 'TEST', 60, " + YEAR + ", now(), now())");

        // Ten at each big school, scoring 10 down to 1. Identical marks at both schools on
        // purpose: any difference in the ranking has to come from the percentile and not
        // from the paper. `scored` does not consent — the gate is set explicitly below,
        // because one of these students must not have it.
        for (int i = 0; i < TEN; i++) {
            scored(FIRST_A + i, CLASS_A, 10 - i);
            scored(FIRST_B + i, CLASS_B, 10 - i);
        }
        for (int i = 0; i < TEN; i++) {
            consent(FIRST_A + i, true);
            consent(FIRST_B + i, true);
        }
        // Four at the small school: under the threshold, so nobody here may be published.
        for (int i = 0; i < 4; i++) {
            scored(FIRST_SMALL + i, SMALL_CLASS, 10 - i);
            consent(FIRST_SMALL + i, true);
        }

        // The most identifying row in the fixture: a perfect paper at a qualifying school,
        // and no consent at all. Opt-in means an account with no row is a no.
        scored(NO_CONSENT_ROW, CLASS_A, 10);

        // And the same again, having said yes and then taken it back.
        scored(CONSENT_REVOKED, CLASS_A, 10);
        consent(CONSENT_REVOKED, false);
    }

    // ── Both schools are in the same table ────────────────────────────────

    @Test
    void aStudentIsComparedWithEverySchoolThatClearsTheThreshold() {
        List<GroupScore> ranked = rows();

        assertThat(ranked).hasSize(2 * TEN);
        assertThat(ranked).extracting(GroupScore::institutionId)
                .containsOnly(SCHOOL_A, SCHOOL_B);
    }

    /**
     * The percentile is the student's standing inside their own school.
     *
     * <p>Both schools sat the same paper and scored the same marks, so a ranking by raw
     * percentage would tie everybody with everybody. Each school's top student comes out
     * at 100 and its bottom at 10, which is the shape a percentile has and a percentage
     * does not.</p>
     */
    @Test
    void thePercentileIsInsideTheStudentsOwnSchool() {
        List<GroupScore> ranked = rows();

        // Same school, same mark, same percentile — the partition is what puts them there.
        assertThat(ranked).filteredOn(score -> score.accountId().equals(FIRST_A))
                .singleElement()
                .satisfies(score -> {
                    assertThat(score.institutionId()).isEqualTo(SCHOOL_A);
                    assertThat(score.percentile()).isEqualTo(100.0);
                });
        assertThat(ranked).filteredOn(score -> score.accountId().equals(FIRST_A + TEN - 1))
                .singleElement()
                .satisfies(score -> assertThat(score.percentile()).isEqualTo(10.0));
        // …and the other school is identical, because it is not folded into the first.
        assertThat(ranked).filteredOn(score -> score.accountId().equals(FIRST_B))
                .singleElement()
                .satisfies(score -> {
                    assertThat(score.institutionId()).isEqualTo(SCHOOL_B);
                    assertThat(score.percentile()).isEqualTo(100.0);
                });
    }

    /**
     * A school of four is identifiable by elimination.
     *
     * <p>The small school is dropped whole, from the rows and from the count. Dropping it
     * from the rows alone would leave {@code totalStudents} claiming students the table
     * does not have, and {@code totalStudents} is what decides the small-group floor.</p>
     */
    @Test
    void aSchoolTooSmallToPublishIsDroppedWhole() {
        List<GroupScore> ranked = rows();

        assertThat(ranked).extracting(GroupScore::accountId)
                .doesNotContain(FIRST_SMALL, FIRST_SMALL + 1, FIRST_SMALL + 2, FIRST_SMALL + 3);
        assertThat(ranked).extracting(GroupScore::institutionId).doesNotContain(SMALL_SCHOOL);
        assertThat(count()).isEqualTo(2L * TEN);
    }

    /**
     * A school appears whole or not at all.
     *
     * <p>The small school has four students and is not in the table. Six more take it to
     * the threshold exactly, and all ten arrive — the four that were already sitting there
     * were not being held back, they were being dropped with the school. Both numbers move
     * together, because a count that disagreed with the rows would decide the small-group
     * floor from a cohort the reader is not being shown.</p>
     */
    @Test
    void aSchoolThatReachesTheThresholdIsPublishedWhole() {
        assertThat(rows()).extracting(GroupScore::institutionId).doesNotContain(SMALL_SCHOOL);
        assertThat(count()).isEqualTo(2L * TEN);

        for (int i = 4; i < TEN; i++) {
            scored(FIRST_SMALL + i, SMALL_CLASS, 10 - i);
            consent(FIRST_SMALL + i, true);
        }

        assertThat(rows()).extracting(GroupScore::institutionId).contains(SMALL_SCHOOL);
        assertThat(rows()).hasSize(3 * TEN);
        assertThat(count()).isEqualTo(3L * TEN);
    }

    // ── Consent ───────────────────────────────────────────────────────────

    /**
     * Two students at a qualifying school, one with a perfect paper, both unranked.
     *
     * <p>One has no consent row at all and one revoked theirs. Both are absent from the
     * rows and from the count — the count matters as much as the rows, because a cohort
     * that still contains them reports a table the reader is not being shown.</p>
     */
    @Test
    void aStudentWhoHasNotOptedInIsNeitherRankedNorCounted() {
        List<GroupScore> ranked = rows();

        assertThat(ranked).extracting(GroupScore::accountId)
                .doesNotContain(NO_CONSENT_ROW, CONSENT_REVOKED);
        assertThat(count()).isEqualTo(2L * TEN);
    }

    @Test
    void optingInIsWhatPutsAStudentInTheTable() {
        consent(NO_CONSENT_ROW, true);

        assertThat(rows()).extracting(GroupScore::accountId).contains(NO_CONSENT_ROW);
        assertThat(count()).isEqualTo(2L * TEN + 1);
    }

    @Test
    void takingTheConsentBackRemovesTheStudentAgain() {
        consent(NO_CONSENT_ROW, true);
        assertThat(rows()).extracting(GroupScore::accountId).contains(NO_CONSENT_ROW);

        consent(NO_CONSENT_ROW, false);

        assertThat(rows()).extracting(GroupScore::accountId).doesNotContain(NO_CONSENT_ROW);
        assertThat(count()).isEqualTo(2L * TEN);
    }

    /** The switch is a row that changes, not a row that accumulates. */
    @Test
    void theConsentIsOneRowPerStudentHoweverOftenItChanges() {
        consent(NO_CONSENT_ROW, true);
        consent(NO_CONSENT_ROW, false);
        consent(NO_CONSENT_ROW, true);

        assertThat(consentRowsOf(NO_CONSENT_ROW)).isEqualTo(1L);
        assertThat(leaderboard.consentedToCrossInstitution(NO_CONSENT_ROW).block()).isTrue();
    }

    /** A refusal is recorded as a refusal, so "said no" and "never asked" stay apart. */
    @Test
    void aRevokedConsentKeepsItsRevocationDate() {
        consent(NO_CONSENT_ROW, true);
        consent(NO_CONSENT_ROW, false);

        assertThat(leaderboard.consentedToCrossInstitution(NO_CONSENT_ROW).block()).isFalse();
        assertThat(revokedAtOf(NO_CONSENT_ROW))
                .as("a revoked row is not a fresh yes")
                .isTrue();
    }

    // ── The count that decides the podium ─────────────────────────────────

    /**
     * The count is "ranked or not", the way a class's is.
     *
     * <p>A student at a qualifying school who has consented and sat nothing is in the
     * cohort and not in the table. That gap is the whole reason both numbers are
     * reported, and it has to survive the consent and threshold filters or
     * {@code rankedStudents} would always equal {@code totalStudents}.</p>
     */
    @Test
    void aConsentingStudentWhoSatNothingIsCountedButNotRanked() {
        long satNothing = FIRST_B + TEN;
        account(satNothing);
        exec("INSERT INTO enrollments (id, account_id, class_id, school_year_id, status,"
                + " created_at, updated_at)"
                + " VALUES (" + satNothing + ", " + satNothing + ", " + CLASS_B + ", " + YEAR + ","
                + " 'ACTIVE', now(), now())");
        consent(satNothing, true);

        assertThat(rows()).extracting(GroupScore::accountId).doesNotContain(satNothing);
        assertThat(count()).isEqualTo(2L * TEN + 1);
    }

    /** One school crossing the threshold moves both numbers together, by the same amount. */
    @Test
    void theCountAndTheRowsMoveTogetherWhenAnInstitutionIsAdded() {
        long before = count();
        long rowsBefore = rows().size();

        for (int i = 4; i < TEN; i++) {
            scored(FIRST_SMALL + i, SMALL_CLASS, 10 - i);
            consent(FIRST_SMALL + i, true);
        }

        // The school contributes all ten of its students, not only the six just added,
        // and the count agrees with the rows to the unit.
        assertThat(rows().size()).isEqualTo(rowsBefore + TEN);
        assertThat(count()).isEqualTo(before + TEN);
        assertThat(count()).isEqualTo(rows().size());
    }

    // ── The year, and the accounts ────────────────────────────────────────

    /**
     * The year still bounds the cohort.
     *
     * <p>school_year_id is one global row per calendar span that every school shares, so
     * without the year on both the seats and the papers this would rank last year's
     * results against this year's students.</p>
     */
    @Test
    void aMarkFromAnotherYearIsNotRanked() {
        exec("UPDATE simulations SET school_year_id = 1 WHERE account_id = " + FIRST_A);

        assertThat(rows()).extracting(GroupScore::accountId).doesNotContain(FIRST_A);
        // Nine left at school A, which is one under the threshold, so the school drops out.
        assertThat(rankedInstitutions()).containsExactly(SCHOOL_B);
    }

    @Test
    void aDeactivatedAccountDropsOut() {
        exec("UPDATE accounts SET active = false WHERE id = " + FIRST_A);

        // School A is down to nine and is dropped whole rather than published short.
        assertThat(rankedInstitutions()).containsExactly(SCHOOL_B);
        assertThat(count()).isEqualTo((long) TEN);
    }

    @Test
    void aCallerInNoSchoolIsRankedAgainstEverybodyRatherThanNobody() {
        // The caller only needs a year to name the cohort. Out here they are not the
        // filter, so a null institution cannot isolate the table and must not empty it.
        List<GroupScore> ranked = leaderboard
                .findAverages(Scope.GLOBAL, null, YEAR, null, null, null, null)
                .collectList().block();

        assertThat(ranked).hasSize(2 * TEN);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private List<GroupScore> rows() {
        return leaderboard
                .findAverages(Scope.GLOBAL, null, YEAR, null, null, null, null)
                .collectList().block();
    }

    private long count() {
        return leaderboard.countMembers(Scope.GLOBAL, null, YEAR, null).block();
    }

    private List<Long> rankedInstitutions() {
        return rows().stream().map(GroupScore::institutionId).distinct().toList();
    }

    private long count(String sql) {
        return database.sql(sql).map((row, metadata) -> ((Number) row.get("total")).longValue())
                .one().defaultIfEmpty(0L).block();
    }

    private void exec(String sql) {
        database.sql(sql).fetch().rowsUpdated().block();
    }

    private long consentRowsOf(long account) {
        return count("SELECT COUNT(*) AS total FROM leaderboard_consents WHERE account_id = " + account);
    }

    private boolean revokedAtOf(long account) {
        return database.sql("SELECT revoked_at IS NOT NULL AS revoked FROM leaderboard_consents"
                        + " WHERE account_id = " + account)
                .map((row, metadata) -> row.get("revoked", Boolean.class))
                .one()
                .defaultIfEmpty(false)
                .block();
    }

    /** One school: an institution, a course and a class in the shared school year. */
    private void school(long institution, long klass, String suffix) {
        exec("INSERT INTO institutions (id, code, name, created_at, updated_at)"
                + " VALUES (" + institution + ", 'ESC" + institution + "', 'Escola "
                + suffix + "', now(), now())");
        exec("INSERT INTO courses (id, code, name, institution_id)"
                + " VALUES (" + klass + ", 'C" + klass + "', 'Curso " + suffix + "', "
                + institution + ")");
        exec("INSERT INTO classes (id, code, grade, course_id, school_year_id, institution_id,"
                + " created_at, updated_at)"
                + " VALUES (" + klass + ", 'T" + klass + "', 7, " + klass + ", " + YEAR + ", "
                + institution + ", now(), now())");
    }

    /**
     * An account with an active seat in the class and a finished paper.
     *
     * <p>The enrollment and the simulation borrow the account's own id. The tables have
     * separate sequences, so nothing collides, and it keeps every row this file writes
     * inside one range — which is what the cleanup at the top of {@link #seed()} can
     * actually reach.</p>
     */
    private void scored(long account, long klass, int mark) {
        account(account);
        exec("INSERT INTO enrollments (id, account_id, class_id, school_year_id, status,"
                + " created_at, updated_at)"
                + " VALUES (" + account + ", " + account + ", " + klass + ", "
                + YEAR + ", 'ACTIVE', now(), now())");
        exec("INSERT INTO simulations (id, account_id, statement_id, school_year_id, status,"
                + " final_score, time_spent_seconds, started_at, finished_at, created_at, updated_at)"
                + " VALUES (" + account + ", " + account + ", " + STATEMENT + ", "
                + YEAR + ", 'FINISHED', " + mark + ", 600, now(), now(), now(), now())");
    }

    private void account(long id) {
        exec("INSERT INTO accounts (id, username, email, password_hash, email_verified,"
                + " active, created_at, updated_at, accessibility_extra_time)"
                + " VALUES (" + id + ", 'u" + id + "', 'u" + id + "@x.pt', 'x', true, true,"
                + " now(), now(), false)");
        exec("INSERT INTO users (id, account_id, first_name, last_name, created_at, updated_at)"
                + " VALUES (" + id + ", " + id + ", 'Aluno" + id + "', 'Teste', now(), now())");
    }

    /**
     * Consent, as an upsert.
     *
     * <p>Same statement the application writes, so a seed that means "this student has
     * consented" cannot fail on a row that is already there from a run that died half
     * way — and cannot quietly leave two rows disagreeing either.</p>
     */
    private void consent(long account, boolean yes) {
        leaderboard.setCrossInstitutionConsent(account, yes).block();
    }
}
