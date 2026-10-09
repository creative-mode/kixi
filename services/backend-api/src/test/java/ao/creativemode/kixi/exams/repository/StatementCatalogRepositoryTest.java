package ao.creativemode.kixi.exams.repository;

import static org.assertj.core.api.Assertions.assertThat;

import ao.creativemode.kixi.exams.repository.StatementCatalogRepository.Criteria;
import ao.creativemode.kixi.exams.repository.StatementCatalogRepository.Sort;
import ao.creativemode.kixi.exams.repository.StatementCatalogRepository.Sql;
import org.junit.jupiter.api.Test;

/**
 * The catalog SQL is built from the filters that are present, so what matters is that
 * an absent filter adds nothing, a present one is bound and never concatenated, and the
 * order and page window stay out of the count.
 */
class StatementCatalogRepositoryTest {

    @Test
    void withNoFiltersOnlyVisibleLiveStatementsAreListedNewestFirst() {
        Sql sql = StatementCatalogRepository.build(criteria(Sort.RELEVANCE, null, null));

        assertThat(sql.select())
                .contains("s.deleted_at IS NULL")
                .contains("s.visible = TRUE")
                .contains("ORDER BY s.created_at DESC, s.id DESC")
                .contains("LIMIT :limit OFFSET :offset")
                .doesNotContain("CASE");
        assertThat(sql.binds()).containsOnlyKeys("limit", "offset");
        assertThat(sql.count()).startsWith("SELECT COUNT(*)").doesNotContain("ORDER BY").doesNotContain("LIMIT");
        assertThat(sql.countBinds()).isEmpty();
    }

    @Test
    void everyFilterIsBoundAndLooksThroughTheClassForSchoolCourseAndYear() {
        Criteria criteria = new Criteria(4L, 40L, 3L, 12, 5L, 2024L, 1L, "Exame Final", "matemática",
                null, null, Sort.RECENT, 20, 0);

        Sql sql = StatementCatalogRepository.build(criteria);

        assertThat(sql.select())
                .contains("COALESCE(s.institution_id, cl.institution_id, co.institution_id) = :institutionId")
                .contains("COALESCE(s.course_id, cl.course_id) = :courseId")
                .contains("s.class_id = :classId")
                .contains("cl.grade = :grade")
                .contains("s.subject_id = :subjectId")
                .contains("COALESCE(s.school_year_id, cl.school_year_id) = :schoolYearId")
                .contains("s.term_id = :termId")
                .contains("LOWER(s.exam_type) = LOWER(:examType)")
                .contains("(s.title ILIKE :query OR s.exam_type ILIKE :query)")
                .doesNotContain("matemática")
                .doesNotContain("Exame Final");
        assertThat(sql.countBinds())
                .containsEntry("institutionId", 4L)
                .containsEntry("courseId", 40L)
                .containsEntry("classId", 3L)
                .containsEntry("grade", 12)
                .containsEntry("subjectId", 5L)
                .containsEntry("schoolYearId", 2024L)
                .containsEntry("termId", 1L)
                .containsEntry("examType", "Exame Final")
                .containsEntry("query", "%matemática%");
        assertThat(sql.count()).contains(":institutionId").contains("cl.grade = :grade");
    }

    @Test
    void relevancePutsTheOwnClassThenTheOwnCourseFirstWithoutFilteringOnThem() {
        Sql sql = StatementCatalogRepository.build(criteria(Sort.RELEVANCE, 3L, 40L));

        assertThat(sql.select()).contains(
                "ORDER BY CASE WHEN s.class_id = :affinityClassId THEN 0 "
                        + "WHEN COALESCE(s.course_id, cl.course_id) = :affinityCourseId THEN 1 "
                        + "ELSE 2 END, s.created_at DESC, s.id DESC");
        assertThat(sql.binds()).containsEntry("affinityClassId", 3L).containsEntry("affinityCourseId", 40L);
        // Affinity only orders: it is neither a filter nor part of the count.
        assertThat(sql.count()).doesNotContain("affinity");
        assertThat(sql.countBinds()).doesNotContainKeys("affinityClassId", "affinityCourseId");
    }

    @Test
    void relevanceWithOnlyACourseStillRanksItFirst() {
        Sql sql = StatementCatalogRepository.build(criteria(Sort.RELEVANCE, null, 40L));

        assertThat(sql.select())
                .contains("CASE WHEN COALESCE(s.course_id, cl.course_id) = :affinityCourseId THEN 1 ELSE 2 END")
                .doesNotContain(":affinityClassId");
    }

    @Test
    void theOtherSortsIgnoreTheAffinityAndEndOnTheId() {
        assertThat(StatementCatalogRepository.build(criteria(Sort.RECENT, 3L, 40L)).select())
                .contains("ORDER BY s.created_at DESC, s.id DESC").doesNotContain("CASE");
        assertThat(StatementCatalogRepository.build(criteria(Sort.OLDEST, 3L, 40L)).select())
                .contains("ORDER BY s.created_at ASC, s.id ASC");
        assertThat(StatementCatalogRepository.build(criteria(Sort.TITLE, 3L, 40L)).select())
                .contains("ORDER BY LOWER(s.title) ASC NULLS LAST, s.id ASC");
    }

    @Test
    void thePageWindowIsBound() {
        Criteria criteria = new Criteria(null, null, null, null, null, null, null, null, null,
                null, null, Sort.RECENT, 10, 30);

        assertThat(StatementCatalogRepository.build(criteria).binds())
                .containsEntry("limit", 10)
                .containsEntry("offset", 30L);
    }

    @Test
    void freeTextIsMatchedLiterally() {
        assertThat(StatementCatalogRepository.escapeLike("100%_a\\b")).isEqualTo("100\\%\\_a\\\\b");
    }

    private static Criteria criteria(Sort sort, Long affinityClassId, Long affinityCourseId) {
        return new Criteria(null, null, null, null, null, null, null, null, null,
                affinityClassId, affinityCourseId, sort, 20, 0);
    }
}
