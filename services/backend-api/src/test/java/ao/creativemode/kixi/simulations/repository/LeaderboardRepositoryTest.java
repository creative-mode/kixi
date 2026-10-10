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
        String sql = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, null, null, null).statement();

        assertThat(sql)
                .contains("s.final_score * 100.0 / st.total_max_score")
                .contains("GROUP BY s.account_id");
    }

    @Test
    void onlyFinishedScoresCount() {
        String sql = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, null, null, null).statement();

        assertThat(sql)
                .contains("s.status = 'FINISHED'")
                .contains("s.deleted_at IS NULL")
                // A score with no scale cannot be turned into a percentage, and would
                // otherwise be compared against one that has a scale.
                .contains("st.total_max_score IS NOT NULL AND st.total_max_score > 0");
    }

    @Test
    void aStatementTakenDownStopsCounting() {
        String sql = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, null, null, null).statement();

        assertThat(sql).contains("st.deleted_at IS NULL");
    }

    @Test
    void roundsBeforeAveragingSoTwoIdenticalPapersProduceIdenticalAverages() {
        String sql = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, null, null, null).statement();

        // Without ROUND, 2/3 can come back as 66.66666666666667 to one student and
        // 66.66666666666666 to another, and the tie rule would never see a tie.
        assertThat(sql).contains("AVG(ROUND(s.final_score * 100.0 / st.total_max_score, 2))");
    }

    @Test
    void theClassScopeFiltersOnTheClass() {
        String sql = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, null, null, null).statement();

        assertThat(sql).contains("e.class_id = :groupId");
    }

    @Test
    void theSchoolScopeFiltersOnTheInstitutionOfTheClass() {
        String sql = LeaderboardRepository.averagesSql(Scope.SCHOOL, 1L, null, null, null).statement();

        assertThat(sql).contains("cl.institution_id = :groupId");
    }

    @Test
    void membershipIsAnExistsSoOneEnrollmentCannotDoubleAStudentsScore() {
        String sql = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, null, null, null).statement();

        assertThat(sql)
                .contains("EXISTS (SELECT 1")
                .contains("e.status = 'ACTIVE'")
                .doesNotContain("JOIN enrollments");
    }

    @Test
    void theStatementAndPeriodFiltersAppearOnlyWhenAskedFor() {
        Sql bare = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, null, null, null);
        assertThat(bare.statement())
                .doesNotContain("s.statement_id = :statementId")
                .doesNotContain("s.finished_at");
        assertThat(bare.binds()).containsOnlyKeys("groupId");

        LocalDateTime from = LocalDateTime.of(2026, 9, 10, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 10, 0, 0);
        Sql filtered = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, 42L, from, to);

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
        String sql = LeaderboardRepository.averagesSql(Scope.CLASS, 7L, null, null, null).statement();

        assertThat(sql).contains("ORDER BY average DESC NULLS LAST, s.account_id ASC");
    }
}