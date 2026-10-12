package ao.creativemode.kixi.simulations.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
     */
    @Test
    void theStatementDoesNotWeldTheWhereToTheFirstCondition() {
        Sql sql = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, 3L, null, null, null, null);

        assertThat(sql.statement()).containsPattern("WHERE\\s+\\S");
        assertThat(sql.statement())
                .as("a keyword welded to the next token")
                .doesNotContainPattern("\\b(WHERE|AND|FROM|JOIN|ON|SET|GROUP BY|ORDER BY)\\S");
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
}