package ao.creativemode.kixi.repository;

import ao.creativemode.kixi.model.QuestionOption;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Repository for QuestionOption entity database operations.
 *
 * Provides reactive CRUD operations and custom queries for
 * managing question options (multiple choice answers) in the database.
 */
@Repository
public interface QuestionOptionRepository extends R2dbcRepository<QuestionOption, Long> {

    /**
     * Find all active (non-deleted) options
     */
    Flux<QuestionOption> findAllByDeletedAtIsNull();

    /**
     * Find all options for a specific question
     */
    Flux<QuestionOption> findAllByQuestionIdAndDeletedAtIsNull(Long questionId);

    /**
     * Find all options for a question, ordered by label
     */
    @Query("SELECT * FROM question_options WHERE question_id = :questionId AND deleted_at IS NULL ORDER BY option_label ASC")
    Flux<QuestionOption> findAllByQuestionIdOrderedByLabel(Long questionId);

    /**
     * Find all options for a question, ordered by order_index
     */
    @Query("SELECT * FROM question_options WHERE question_id = :questionId AND deleted_at IS NULL ORDER BY order_index ASC")
    Flux<QuestionOption> findAllByQuestionIdOrderedByOrderIndex(Long questionId);

    /**
     * Find an active option by ID
     */
    Mono<QuestionOption> findByIdAndDeletedAtIsNull(Long id);

    /**
     * Find a deleted option by ID
     */
    Mono<QuestionOption> findByIdAndDeletedAtIsNotNull(Long id);

    /**
     * Find an option by question ID and label
     */
    Mono<QuestionOption> findByQuestionIdAndOptionLabelAndDeletedAtIsNull(Long questionId, String optionLabel);

    /**
     * Find the correct option(s) for a question
     */
    Flux<QuestionOption> findAllByQuestionIdAndIsCorrectTrueAndDeletedAtIsNull(Long questionId);

    /**
     * Find the single correct option for a question (for single-answer questions)
     */
    @Query("SELECT * FROM question_options WHERE question_id = :questionId AND is_correct = true AND deleted_at IS NULL LIMIT 1")
    Mono<QuestionOption> findCorrectOptionByQuestionId(Long questionId);

    /**
     * Find incorrect options for a question
     */
    Flux<QuestionOption> findAllByQuestionIdAndIsCorrectFalseAndDeletedAtIsNull(Long questionId);

    /**
     * Find options with OCR confidence below threshold
     */
    @Query("SELECT * FROM question_options WHERE ocr_confidence < :threshold AND deleted_at IS NULL ORDER BY ocr_confidence ASC")
    Flux<QuestionOption> findAllWithLowOcrConfidence(Double threshold);

    /**
     * Find options with low OCR confidence for a specific question
     */
    @Query("SELECT * FROM question_options WHERE question_id = :questionId AND ocr_confidence < :threshold AND deleted_at IS NULL ORDER BY option_label ASC")
    Flux<QuestionOption> findByQuestionIdWithLowOcrConfidence(Long questionId, Double threshold);

    /**
     * Count options for a question
     */
    Mono<Long> countByQuestionIdAndDeletedAtIsNull(Long questionId);

    /**
     * Count correct options for a question
     */
    Mono<Long> countByQuestionIdAndIsCorrectTrueAndDeletedAtIsNull(Long questionId);

    /**
     * Check if an option exists by ID and is active
     */
    Mono<Boolean> existsByIdAndDeletedAtIsNull(Long id);

    /**
     * Check if an option with the same label exists for a question
     */
    Mono<Boolean> existsByQuestionIdAndOptionLabelAndDeletedAtIsNull(Long questionId, String optionLabel);

    /**
     * Check if a question has a correct option defined
     */
    Mono<Boolean> existsByQuestionIdAndIsCorrectTrueAndDeletedAtIsNull(Long questionId);

    /**
     * Delete all options for a question (soft delete)
     */
    @Query("UPDATE question_options SET deleted_at = CURRENT_TIMESTAMP WHERE question_id = :questionId AND deleted_at IS NULL")
    Mono<Integer> softDeleteAllByQuestionId(Long questionId);

    /**
     * Find the next order index for a question
     */
    @Query("SELECT COALESCE(MAX(order_index), 0) + 1 FROM question_options WHERE question_id = :questionId AND deleted_at IS NULL")
    Mono<Integer> findNextOrderIndex(Long questionId);

    /**
     * Mark all options as incorrect for a question
     */
    @Query("UPDATE question_options SET is_correct = false, updated_at = CURRENT_TIMESTAMP WHERE question_id = :questionId AND deleted_at IS NULL")
    Mono<Integer> markAllAsIncorrect(Long questionId);

    /**
     * Mark a specific option as correct (and others as incorrect)
     */
    @Query("UPDATE question_options SET is_correct = (id = :correctOptionId), updated_at = CURRENT_TIMESTAMP WHERE question_id = :questionId AND deleted_at IS NULL")
    Mono<Integer> setCorrectOption(Long questionId, Long correctOptionId);

    /**
     * Search options by text content (case-insensitive partial match)
     */
    @Query("SELECT * FROM question_options WHERE LOWER(option_text) LIKE LOWER(CONCAT('%', :searchTerm, '%')) AND deleted_at IS NULL ORDER BY question_id, option_label")
    Flux<QuestionOption> searchByText(String searchTerm);

    /**
     * Get average OCR confidence for options of a question
     */
    @Query("SELECT AVG(ocr_confidence) FROM question_options WHERE question_id = :questionId AND ocr_confidence IS NOT NULL AND deleted_at IS NULL")
    Mono<Double> getAverageOcrConfidence(Long questionId);

    /**
     * Find all options for multiple questions
     */
    @Query("SELECT * FROM question_options WHERE question_id IN (:questionIds) AND deleted_at IS NULL ORDER BY question_id, order_index")
    Flux<QuestionOption> findAllByQuestionIds(Iterable<Long> questionIds);

    /**
     * Bulk delete options for multiple questions
     */
    @Query("UPDATE question_options SET deleted_at = CURRENT_TIMESTAMP WHERE question_id IN (:questionIds) AND deleted_at IS NULL")
    Mono<Integer> softDeleteAllByQuestionIds(Iterable<Long> questionIds);
}
