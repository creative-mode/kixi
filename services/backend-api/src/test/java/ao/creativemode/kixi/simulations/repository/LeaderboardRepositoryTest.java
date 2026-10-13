package ao.creativemode.kixi.simulations.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ao.creativemode.kixi.simulations.repository.LeaderboardRepository.Scope;
import ao.creativemode.kixi.simulations.repository.LeaderboardRepository.Sql;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/**
 * The SQL is the part of the ranking nobody reads in a stack trace, so it is checked
 * here without a database: which group, which filters, and above all that membership is
 * an EXISTS and not a join.
 */
class LeaderboardRepositoryTest {

    @Test
    void ranksPercentagesSoPapersOfDifferentLengthsStayComparable() {
        String sql = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, 1L, null, null, null, null).statement();

        // DECIMAL(10,2) on both sides, so this division and the AVG over it are exact
        // numeric arithmetic. No ROUND: rounding here would average each paper first and
        // turn averages that genuinely differ into a tie.
        assertThat(sql)
                .contains("AVG(s.final_score * 100.0 / st.total_max_score)")
                .doesNotContain("ROUND(")
                .contains("GROUP BY s.account_id");
    }

    @Test
    void onlyFinishedScoresCount() {
        String sql = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, 1L, null, null, null, null).statement();

        assertThat(sql)
                .contains("s.status = 'FINISHED'")
                .contains("s.deleted_at IS NULL")
                // A score with no scale cannot be turned into a percentage, and would
                // otherwise be compared against one that has a scale.
                .contains("st.total_max_score IS NOT NULL AND st.total_max_score > 0");
    }

    @Test
    void theCohortIsPinnedToTheSchoolYear() {
        // A student who moves keeps their old row ACTIVE forever, so without the year on
        // both sides they go on being ranked where they no longer are — under their name.
        Sql sql = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, 3L, null, null, null, null);

        assertThat(sql.statement())
                .contains("e.school_year_id = :schoolYearId")
                .contains("s.school_year_id = :schoolYearId");
        assertThat(sql.binds()).containsEntry("schoolYearId", 3L);
    }

    /**
     * No keyword ends up welded to the token after it.
     *
     * <p>A text block strips trailing spaces, so a {@code "WHERE "} on its own line
     * arrives as {@code "WHERE"} and joins the first condition into {@code WHEREs...}.
     * Balanced parentheses and all.</p>
     *
     * <p>Over every scope, because it is the scopes that append conditions to a fragment
     * ending in a keyword that get it — and the cross-institution statements are
     * assembled from more such fragments than any of the others.</p>
     */
    @Test
    void theStatementDoesNotWeldTheWhereToTheFirstCondition() {
        for (Scope scope : Scope.values()) {
            Sql sql = LeaderboardRepository.averagesSql(scope, 7L, 3L, 1L, 7L,
                    LocalDateTime.now(), LocalDateTime.now().plusDays(7));

            assertThat(sql.statement())
                    .as("scope=%s needs a WHERE followed by something", scope)
                    .containsPattern("WHERE\\s+\\S");
            assertThat(sql.statement())
                    .as("a keyword welded to the next token, scope=%s:%n%s", scope, sql.statement())
                    .doesNotContainPattern("\\b(WHERE|AND|FROM|JOIN|ON|SET|GROUP BY|ORDER BY)\\S");
        }
    }

    /** The statement has to be something PostgreSQL will parse.
     *
     * <p>{@code MEMBERSHIP} and {@code FULLY_MARKED} both open an {@code EXISTS} and are
     * concatenated with more conditions, so a parenthesis forgotten at either end leaves
     * the endpoint failing on every single request. The string assertions elsewhere in
     * this class are all satisfied by a statement that will not run; this one is not.
     */
    @Test
    void theStatementHasBalancedParentheses() {
        for (Scope scope : Scope.values()) {
            for (Long statementId : new Long[] {null, 7L}) {
                for (boolean window : new boolean[] {false, true}) {
                    Sql sql = LeaderboardRepository.averagesSql(
                            scope, 7L, 3L, 1L, statementId,
                            window ? LocalDateTime.now() : null,
                            window ? LocalDateTime.now().plusDays(7) : null);

                    assertThat(balance(sql.statement()))
                            .as("unbalanced parentheses for scope=%s statementId=%s window=%s:%n%s",
                                    scope, statementId, window, sql.statement())
                            .isZero();
                }
            }
        }
    }

    /** Opens minus closes, ignoring anything inside a string literal. */
    private static int balance(String statement) {
        String stripped = statement.replaceAll("'(?:[^']|'')*'", "''");
        int opens = 0;
        for (char c : stripped.toCharArray()) {
            if (c == '(') {
                opens++;
            } else if (c == ')') {
                opens--;
            }
        }
        return opens;
    }

    /**
     * The count carries the year too.
     *
     * <p>This one went missing once with the bind left in place and no placeholder in the
     * statement: {@code DatabaseClient} ignores an unmatched bind without a word, so the
     * count kept summing every year and {@code totalStudents} came out inflated while
     * every test stayed green.</p>
     */
    @Test
    void theMemberCountIsPinnedToTheSchoolYearToo() {
        String classScope = LeaderboardRepository.membersSql(Scope.CLASS, 7L);
        String schoolScope = LeaderboardRepository.membersSql(Scope.SCHOOL, 1L);

        assertThat(classScope)
                .contains("e.school_year_id = :schoolYearId")
                .contains("e.class_id = :groupId");
        assertThat(schoolScope)
                .contains("e.school_year_id = :schoolYearId")
                .contains("cl.institution_id = :groupId");
    }

    /**
     * The statement has to parse, which {@code contains} on separate fragments cannot say.
     * A text block drops trailing spaces, so a keyword left at the end of a line welds onto
     * what is appended after it ({@code ANDe.class_id}) and Postgres answers 500.
     */
    @Test
    void noSqlKeywordIsWeldedToTheTokenAfterIt() {
        for (Scope scope : Scope.values()) {
            for (String sql : new String[] {
                    LeaderboardRepository.membersSql(scope, 7L),
                    LeaderboardRepository.averagesSql(scope, 7L, 1L, 1L, 42L, null, null).statement()}) {
                assertThat(sql)
                        .as("%s: a keyword followed directly by an identifier", scope)
                        .doesNotContainPattern("\\b(AND|OR|WHERE|ON)[a-z]");
            }
        }
    }

    @Test
    void theMemberCountExcludesCancelledEnrollmentsAndInactiveAccounts() {
        String sql = LeaderboardRepository.membersSql(Scope.CLASS, 7L);

        assertThat(sql)
                .contains("e.status = 'ACTIVE'")
                .contains("a.active = TRUE")
                .contains("a.deleted_at IS NULL");
    }

    @Test
    void aSimulationWithQuestionsStillWaitingDoesNotScore() {
        // Since #169 an open question is PENDING_REVIEW and final_score only adds up what
        // has been marked. Ranking that against the whole total would park a student below
        // their peers until the teacher got round to them, and the class would see it.
        String sql = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, 1L, null, null, null, null)
                .statement();

        assertThat(sql)
                .contains("NOT EXISTS (SELECT 1")
                .contains("sa.simulation_id = s.id")
                .contains("sa.review_status = 'PENDING_REVIEW'");
    }

    @Test
    void anInactiveAccountIsNotRanked() {
        String sql = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, 1L, null, null, null, null)
                .statement();

        assertThat(sql)
                .contains("JOIN accounts a ON a.id = e.account_id")
                .contains("a.active = TRUE")
                .contains("a.deleted_at IS NULL");
    }

    @Test
    void aStatementTakenDownStopsCounting() {
        String sql = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, 1L, null, null, null, null).statement();

        assertThat(sql).contains("st.deleted_at IS NULL");
    }

    @Test
    void theClassScopeFiltersOnTheClass() {
        String sql = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, 1L, null, null, null, null).statement();

        assertThat(sql).contains("e.class_id = :groupId");
    }

    @Test
    void theSchoolScopeFiltersOnTheInstitutionOfTheClass() {
        String sql = LeaderboardRepository.averagesSql(Scope.SCHOOL, 1L, 1L, null, null, null, null).statement();

        assertThat(sql).contains("cl.institution_id = :groupId");
    }

    @Test
    void membershipIsAnExistsSoOneEnrollmentCannotDoubleAStudentsScore() {
        String sql = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, 1L, null, null, null, null).statement();

        assertThat(sql)
                .contains("EXISTS (SELECT 1")
                .contains("e.status = 'ACTIVE'")
                .doesNotContain("JOIN enrollments");
    }

    @Test
    void theStatementAndPeriodFiltersAppearOnlyWhenAskedFor() {
        Sql bare = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, 1L, null, null, null, null);
        assertThat(bare.statement())
                .doesNotContain("s.statement_id = :statementId")
                .doesNotContain("s.finished_at");
        assertThat(bare.binds()).containsOnlyKeys("groupId", "schoolYearId");

        LocalDateTime from = LocalDateTime.of(2026, 9, 10, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 10, 0, 0);
        Sql filtered = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, 1L, null, 42L, from, to);

        assertThat(filtered.statement())
                .contains("s.statement_id = :statementId")
                .contains("s.finished_at >= :from")
                .contains("s.finished_at < :to");
        assertThat(filtered.binds())
                .containsEntry("statementId", 42L)
                .containsEntry("from", from)
                .containsEntry("to", to)
                .containsEntry("groupId", 7L);
    }

    @Test
    void tiesAlwaysBreakOnTheAccountSoACachedRankingIsStable() {
        String sql = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, 1L, null, null, null, null).statement();

        assertThat(sql).contains("ORDER BY average DESC NULLS LAST, s.account_id ASC");
    }

    // ── Across institutions (issue #189, ADR-0013) ────────────────────────

    private static String globalSql() {
        return LeaderboardRepository.averagesSql(Scope.GLOBAL, null, 1L, null, null, null, null).statement();
    }

    /** The group id is what a class or a school is picked by, and there is neither here. */
    @Test
    void theGlobalScopeIsNotFilteredByAnySchool() {
        String sql = globalSql();

        assertThat(sql)
                .doesNotContain(":groupId")
                .doesNotContain("e.class_id = ")
                .doesNotContain("cl.institution_id = ")
                .doesNotContain("sd.institution_id = :");
    }

    @Test
    void theGlobalScopeBindsNoGroupIdAtAll() {
        Sql sql = LeaderboardRepository.averagesSql(Scope.GLOBAL, null, 1L, null, null, null, null);

        // An unmatched bind is dropped without a word, so this is not a crash — it is a
        // group id that has quietly stopped meaning anything, which is worse.
        assertThat(sql.binds()).containsOnlyKeys("schoolYearId", "institutionMinStudents");
        assertThat(LeaderboardRepository.membersSql(Scope.GLOBAL, 7L))
                .doesNotContain(":groupId");
    }

    /** A ranking that crosses the school boundary is the one that needs a yes first. */
    @Test
    void theGlobalScopeRanksNobodyWhoHasNotConsented() {
        assertThat(globalSql())
                .contains("FROM leaderboard_consents lc")
                .contains("lc.cross_institution = TRUE")
                .contains("lc.revoked_at IS NULL");
        // The other scopes publish to peers and are not gated: they must not grow one.
        assertThat(LeaderboardRepository.averagesSql(Scope.CLASS, 7L, 1L, null, null, null, null)
                .statement()).doesNotContain("leaderboard_consents");
        assertThat(LeaderboardRepository.averagesSql(Scope.SCHOOL, 1L, 1L, null, null, null, null)
                .statement()).doesNotContain("leaderboard_consents");
    }

    /** One row per student, newest seat first — the rule the seat reader applies. */
    @Test
    void theGlobalScopePicksOneSchoolPerStudentInsteadOfJoiningEverySeat() {
        String sql = globalSql();

        // Joining enrollments straight into the aggregate would repeat a student's
        // simulations once per matching row; the DISTINCT ON collapses the seat first.
        assertThat(sql)
                .contains("SELECT DISTINCT ON (e.account_id)")
                .contains("ORDER BY e.account_id, e.created_at DESC, e.id DESC")
                .contains("JOIN seat sd ON sd.account_id = s.account_id");
    }

    /**
     * A school of three is identifiable by elimination — one student has a determinate
     * rank — so it is dropped whole, before the rows come back, not filtered afterwards.
     */
    @Test
    void theGlobalScopeDropsInstitutionsTooSmallToPublish() {
        String sql = globalSql();

        assertThat(sql)
                .contains("HAVING COUNT(*) >= :institutionMinStudents")
                .contains("FROM per_account")
                .contains("WHERE pa.institution_id IN (SELECT institution_id FROM big_enough)");
        assertThat(LeaderboardRepository.membersSql(Scope.GLOBAL, null))
                .contains("WHERE sd.institution_id IN (SELECT institution_id FROM big_enough)");
    }

    @Test
    void theGlobalScopeRanksOnPercentileInsideTheStudentsOwnSchool() {
        String sql = globalSql();

        assertThat(sql)
                .contains("PARTITION BY pa.institution_id")
                .contains("CUME_DIST() OVER")
                // 80% on an easy paper and 80% on a hard paper are different results.
                // The raw average may break a tie, and is returned, but must not decide.
                .contains("ORDER BY percentile DESC, pa.average DESC, pa.account_id ASC")
                .doesNotContain("ORDER BY pa.average DESC");
    }

    /**
     * The count has to be asking the same question as the rows.
     *
     * <p>{@code totalStudents} decides the small-group floor, so a count that disagreed
     * with the rows would decide a protection from a cohort the table is not. Both are
     * built from one shared block of CTEs, and this is what holds them to it: the count
     * statement has to contain the rows' CTE block verbatim.</p>
     */
    @Test
    void theGlobalCountIsBuiltFromTheSameConditionsAsTheGlobalRows() {
        String rows = globalSql();
        String count = LeaderboardRepository.membersSql(Scope.GLOBAL, null);
        int firstProjection = rows.indexOf("SELECT pa.account_id");

        assertThat(count).contains(rows.substring(0, firstProjection));
        // …and the consent gate on both sides, over the column each one hangs off.
        assertThat(count).contains("lc.account_id = sd.account_id");
        assertThat(rows).contains("lc.account_id = s.account_id");
    }

    @Test
    void theMemberCountForGlobalIsPinnedToTheSchoolYearAndTheThreshold() {
        assertThat(LeaderboardRepository.membersSql(Scope.GLOBAL, null))
                .contains("e.school_year_id = :schoolYearId")
                .contains("HAVING COUNT(*) >= :institutionMinStudents");
    }

    /** A global ranking has no group id, so the group predicate must refuse it loudly. */
    @Test
    void aGlobalRankingCannotBeTurnedIntoAClassOrASchool() {
        assertThatThrownBy(() -> LeaderboardRepository.groupPredicate(
                Scope.GLOBAL, 7L, "e", "cl"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GLOBAL");
    }
}