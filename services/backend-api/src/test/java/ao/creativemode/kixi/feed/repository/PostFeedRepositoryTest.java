package ao.creativemode.kixi.feed.repository;

import ao.creativemode.kixi.feed.repository.PostFeedRepository.Sql;
import ao.creativemode.kixi.shared.service.FeedScopeAuthorizer.FeedScope;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SQL behind the feed is checked here without a database, following
 * {@code LeaderboardRepositoryTest}: above all, that the scope the server
 * resolved is what gates every row, never an id the caller supplied on its
 * own (issue #120, review points 3 and 5).
 */
class PostFeedRepositoryTest {

    private static final FeedScope SCOPE = new FeedScope(
            Set.of(1L), Set.of(10L), Set.of(20L), Set.of(2026L));

    @Test
    void institutionIsAlwaysTheHardGate() {
        Sql sql = PostFeedRepository.feedSql(SCOPE, null, null, null, 10, 0);

        assertThat(sql.statement()).contains("institution_id = ANY(:institutionIds)");
        assertThat((Long[]) sql.binds().get("institutionIds")).containsExactly(1L);
    }

    @Test
    void withoutNarrowingTheClassCourseAndSchoolYearSetsStillGateTheRows() {
        Sql sql = PostFeedRepository.feedSql(SCOPE, null, null, null, 10, 0);

        assertThat(sql.statement())
                .contains("class_id = ANY(:classIds)")
                .contains("course_id = ANY(:courseIds)")
                .contains("school_year_id = ANY(:schoolYearIds)");
        assertThat((Long[]) sql.binds().get("classIds")).containsExactly(10L);
        assertThat((Long[]) sql.binds().get("courseIds")).containsExactly(20L);
        assertThat((Long[]) sql.binds().get("schoolYearIds")).containsExactly(2026L);
    }

    @Test
    void anEmptyScopeBindsEmptyArraysRatherThanDroppingTheFilter() {
        Sql sql = PostFeedRepository.feedSql(FeedScope.NONE, null, null, null, 10, 0);

        assertThat((Long[]) sql.binds().get("institutionIds")).isEmpty();
        assertThat((Long[]) sql.binds().get("classIds")).isEmpty();
    }

    @Test
    void aRequestedClassNarrowsButDoesNotReplaceTheScope() {
        Sql sql = PostFeedRepository.feedSql(SCOPE, null, null, 10L, 10, 0);

        assertThat(sql.statement())
                .contains("institution_id = ANY(:institutionIds)")
                .contains("class_id = :classId");
        assertThat(sql.binds()).containsEntry("classId", 10L);
    }

    @Test
    void hiddenAndDeletedPostsAreNeverReturned() {
        Sql sql = PostFeedRepository.feedSql(SCOPE, null, null, null, 10, 0);

        assertThat(sql.statement())
                .contains("is_hidden = false")
                .contains("deleted_at IS NULL");
    }

    @Test
    void feedIsOrderedNewestFirstWithATiebreaker() {
        Sql sql = PostFeedRepository.feedSql(SCOPE, null, null, null, 10, 0);

        assertThat(sql.statement()).contains("ORDER BY created_at DESC, id DESC");
    }

    @Test
    void countUsesTheSameScopeAsTheFeed() {
        Sql countSql = PostFeedRepository.countSql(SCOPE, null, 20L, null);

        assertThat(countSql.statement())
                .contains("SELECT COUNT(*)")
                .contains("institution_id = ANY(:institutionIds)")
                .contains("course_id = :courseId")
                .doesNotContain("LIMIT");
        assertThat((Long[]) countSql.binds().get("institutionIds")).containsExactly(1L);
        assertThat(countSql.binds()).containsEntry("courseId", 20L);
    }
}
