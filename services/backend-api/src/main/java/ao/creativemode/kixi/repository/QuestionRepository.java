package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.Question;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Repository for Question entity database operations.
 *
 * Provides reactive CRUD operations and custom queries for
 * managing exam questions in the database.
 */
@Repository
public interface QuestionRepository extends R2dbcRepository<Question, Long> {

    /**
     * Find all active (non-deleted) questions
     */
    Flux<Question> findAllByDeletedAtIsNull();

    /**
     * Find all questions for a specific statement
     */
    Flux<Question> findAllByStatementIdAndDeletedAtIsNull(Long statementId);

    /**
     * Find all questions for a statement, ordered by question number
     */
    @Query("SELECT * FROM questions WHERE statement_id = :statementId AND deleted_at IS NULL ORDER BY number ASC")
    Flux<Question> findAllByStatementIdOrderedByNumber(Long statementId);

    /**
     * Find all questions for a statement, ordered by order_index
     */
    @Query("SELECT * FROM questions WHERE statement_id = :statementId AND deleted_at IS NULL ORDER BY order_index ASC")
    Flux<Question> findAllByStatementIdOrderedByOrderIndex(Long statementId);

    /**
     * Find an active question by ID
     */
    Mono<Question> findByIdAndDeletedAtIsNull(Long id);

    /**
     * Find a deleted question by ID
     */
    Mono<Question> findByIdAndDeletedAtIsNotNull(Long id);

    /**
     * Find a question by statement ID and question number
     */
    Mono<Question> findByStatementIdAndNumberAndDeletedAtIsNull(Long statementId, Integer number);

    /**
     * Find all questions that need review
     */
    Flux<Question> findAllByNeedsReviewTrueAndDeletedAtIsNull();

    /**
     * Find all questions for a statement that need review
     */
    Flux<Question> findAllByStatementIdAndNeedsReviewTrueAndDeletedAtIsNull(Long statementId);

    /**
     * Find questions by type
     */
    Flux<Question> findAllByQuestionTypeAndDeletedAtIsNull(String questionType);

    /**
     * Find questions by type for a specific statement
     */
    Flux<Question> findAllByStatementIdAndQuestionTypeAndDeletedAtIsNull(Long statementId, String questionType);

    /**
     * Find multiple choice questions for a statement
     */
    @Query("SELECT * FROM questions WHERE statement_id = :statementId AND question_type = 'multiple_choice' AND deleted_at IS NULL ORDER BY number ASC")
    Flux<Question> findMultipleChoiceByStatementId(Long statementId);

    /**
     * Find questions with OCR confidence below threshold
     */
    @Query("SELECT * FROM questions WHERE ocr_confidence < :threshold AND deleted_at IS NULL ORDER BY ocr_confidence ASC")
    Flux<Question> findAllWithLowOcrConfidence(Double threshold);

    /**
     * Find questions with low OCR confidence for a specific statement
     */
    @Query("SELECT * FROM questions WHERE statement_id = :statementId AND ocr_confidence < :threshold AND deleted_at IS NULL ORDER BY number ASC")
    Flux<Question> findByStatementIdWithLowOcrConfidence(Long statementId, Double threshold);

    /**
     * Find questions on a specific page
     */
    Flux<Question> findAllByPageIndexAndDeletedAtIsNull(Integer pageIndex);

    /**
     * Find questions on a specific page for a statement
     */
    Flux<Question> findAllByStatementIdAndPageIndexAndDeletedAtIsNull(Long statementId, Integer pageIndex);

    /**
     * Count questions for a statement
     */
    Mono<Long> countByStatementIdAndDeletedAtIsNull(Long statementId);

    /**
     * Count questions needing review
     */
    Mono<Long> countByNeedsReviewTrueAndDeletedAtIsNull();

    /**
     * Count questions by type for a statement
     */
    Mono<Long> countByStatementIdAndQuestionTypeAndDeletedAtIsNull(Long statementId, String questionType);

    /**
     * Check if a question exists by ID and is active
     */
    Mono<Boolean> existsByIdAndDeletedAtIsNull(Long id);

    /**
     * Check if a question with the same number exists in a statement
     */
    Mono<Boolean> existsByStatementIdAndNumberAndDeletedAtIsNull(Long statementId, Integer number);

    /**
     * Delete all questions for a statement (soft delete)
     */
    @Query("UPDATE questions SET deleted_at = CURRENT_TIMESTAMP WHERE statement_id = :statementId AND deleted_at IS NULL")
    Mono<Integer> softDeleteAllByStatementId(Long statementId);

    /**
     * Calculate total max score for a statement
     */
    @Query("SELECT COALESCE(SUM(max_score), 0) FROM questions WHERE statement_id = :statementId AND deleted_at IS NULL")
    Mono<Double> calculateTotalMaxScore(Long statementId);

    /**
     * Find the next order index for a statement
     */
    @Query("SELECT COALESCE(MAX(order_index), 0) + 1 FROM questions WHERE statement_id = :statementId AND deleted_at IS NULL")
    Mono<Integer> findNextOrderIndex(Long statementId);

    /**
     * Find the next question number for a statement
     */
    @Query("SELECT COALESCE(MAX(number), 0) + 1 FROM questions WHERE statement_id = :statementId AND deleted_at IS NULL")
    Mono<Integer> findNextQuestionNumber(Long statementId);

    /**
     * Search questions by text content (case-insensitive partial match)
     */
    @Query("SELECT * FROM questions WHERE LOWER(text) LIKE LOWER(CONCAT('%', :searchTerm, '%')) AND deleted_at IS NULL ORDER BY statement_id, number")
    Flux<Question> searchByText(String searchTerm);

    /**
     * Find questions with specific score range
     */
    @Query("SELECT * FROM questions WHERE max_score >= :minScore AND max_score <= :maxScore AND deleted_at IS NULL ORDER BY max_score DESC")
    Flux<Question> findByScoreRange(Double minScore, Double maxScore);

    /**
     * Get average OCR confidence for a statement
     */
    @Query("SELECT AVG(ocr_confidence) FROM questions WHERE statement_id = :statementId AND ocr_confidence IS NOT NULL AND deleted_at IS NULL")
    Mono<Double> getAverageOcrConfidence(Long statementId);
}
