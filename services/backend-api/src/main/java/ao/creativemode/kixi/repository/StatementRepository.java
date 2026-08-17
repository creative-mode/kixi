package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.Statement;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Repository for Statement entity database operations.
 *
 * Provides reactive CRUD operations and custom queries for
 * managing exam statements in the database.
 */
@Repository
public interface StatementRepository extends R2dbcRepository<Statement, Long> {

    /**
     * Find all active (non-deleted) statements
     */
    Flux<Statement> findAllByDeletedAtIsNull();

    /**
     * Find all soft-deleted statements
     */
    Flux<Statement> findAllByDeletedAtIsNotNull();

    /**
     * Find an active statement by ID
     */
    Mono<Statement> findByIdAndDeletedAtIsNull(Long id);

    /**
     * Find a visible active statement by ID.
     */
    Mono<Statement> findByIdAndVisibleTrueAndDeletedAtIsNull(Long id);

    /**
     * Find a deleted statement by ID
     */
    Mono<Statement> findByIdAndDeletedAtIsNotNull(Long id);

    /**
     * Find all visible statements
     */
    Flux<Statement> findAllByVisibleTrueAndDeletedAtIsNull();

    /**
     * Find all statements that need review
     */
    Flux<Statement> findAllByNeedsReviewTrueAndDeletedAtIsNull();

    /**
     * Find statements by school year
     */
    Flux<Statement> findAllBySchoolYearIdAndDeletedAtIsNull(Long schoolYearId);

    /**
     * Find visible statements by school year.
     */
    Flux<Statement> findAllByVisibleTrueAndSchoolYearIdAndDeletedAtIsNull(Long schoolYearId);

    /**
     * Find statements by subject
     */
    Flux<Statement> findAllBySubjectIdAndDeletedAtIsNull(Long subjectId);

    /**
     * Find visible statements by subject.
     */
    Flux<Statement> findAllByVisibleTrueAndSubjectIdAndDeletedAtIsNull(Long subjectId);

    /**
     * Find statements by term
     */
    Flux<Statement> findAllByTermIdAndDeletedAtIsNull(Long termId);

    /**
     * Find statements by class
     */
    Flux<Statement> findAllByClassIdAndDeletedAtIsNull(Long classId);

    /**
     * Find statements by school year and subject
     */
    Flux<Statement> findAllBySchoolYearIdAndSubjectIdAndDeletedAtIsNull(Long schoolYearId, Long subjectId);

    /**
     * Find statements by school year, term, and subject
     */
    Flux<Statement> findAllBySchoolYearIdAndTermIdAndSubjectIdAndDeletedAtIsNull(
            Long schoolYearId, Long termId, Long subjectId);

    /**
     * Find statements created by a specific user
     */
    Flux<Statement> findAllByCreatedByAndDeletedAtIsNull(Long createdBy);

    /**
     * Find statements by source (manual, ocr, import)
     */
    Flux<Statement> findAllBySourceAndDeletedAtIsNull(String source);

    /**
     * Find statements created via OCR
     */
    @Query("SELECT * FROM statements WHERE source = 'ocr' AND deleted_at IS NULL ORDER BY created_at DESC")
    Flux<Statement> findAllFromOcr();

    /**
     * Find statements by OCR request ID
     */
    Mono<Statement> findByOcrRequestIdAndDeletedAtIsNull(String ocrRequestId);

    /**
     * Find statements with OCR confidence below threshold
     */
    @Query("SELECT * FROM statements WHERE ocr_confidence < :threshold AND deleted_at IS NULL ORDER BY ocr_confidence ASC")
    Flux<Statement> findAllWithLowOcrConfidence(Double threshold);

    /**
     * Find statements by exam type
     */
    Flux<Statement> findAllByExamTypeAndDeletedAtIsNull(String examType);

    /**
     * Find statements by variant
     */
    Flux<Statement> findAllByVariantAndDeletedAtIsNull(String variant);

    /**
     * Count active statements
     */
    Mono<Long> countByDeletedAtIsNull();

    /**
     * Count statements needing review
     */
    Mono<Long> countByNeedsReviewTrueAndDeletedAtIsNull();

    /**
     * Count statements by source
     */
    Mono<Long> countBySourceAndDeletedAtIsNull(String source);

    /**
     * Check if a statement exists by ID and is active
     */
    Mono<Boolean> existsByIdAndDeletedAtIsNull(Long id);

    /**
     * Search statements by title (case-insensitive partial match)
     */
    @Query("SELECT * FROM statements WHERE LOWER(title) LIKE LOWER(CONCAT('%', :searchTerm, '%')) AND deleted_at IS NULL ORDER BY created_at DESC")
    Flux<Statement> searchByTitle(String searchTerm);

    /**
     * Search only visible active statements by title.
     */
    @Query("SELECT * FROM statements WHERE visible = TRUE AND LOWER(title) LIKE LOWER(CONCAT('%', :searchTerm, '%')) AND deleted_at IS NULL ORDER BY created_at DESC")
    Flux<Statement> searchVisibleByTitle(String searchTerm);

    /**
     * Find recent statements with pagination
     */
    @Query("SELECT * FROM statements WHERE deleted_at IS NULL ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Statement> findRecentStatements(int limit, int offset);

    /**
     * Find statements by multiple filters
     */
    @Query("""
        SELECT * FROM statements
        WHERE deleted_at IS NULL
        AND (:schoolYearId IS NULL OR school_year_id = :schoolYearId)
        AND (:termId IS NULL OR term_id = :termId)
        AND (:subjectId IS NULL OR subject_id = :subjectId)
        AND (:classId IS NULL OR class_id = :classId)
        AND (:examType IS NULL OR exam_type = :examType)
        ORDER BY created_at DESC
        """)
    Flux<Statement> findByFilters(
            Long schoolYearId,
            Long termId,
            Long subjectId,
            Long classId,
            String examType);
}
