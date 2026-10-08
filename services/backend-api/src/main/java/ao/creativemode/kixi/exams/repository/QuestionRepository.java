package ao.creativemode.kixi.exams.repository;

import ao.creativemode.kixi.exams.model.Question;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;

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

    Flux<Question> findAllByStatementIdAndDeletedAtIsNotNull(Long statementId);

    /**
     * Find all questions for a statement, ordered by question number
     */
    @Query(
        "SELECT * FROM questions WHERE statement_id = :statementId AND deleted_at IS NULL ORDER BY number ASC"
    )
    Flux<Question> findAllByStatementIdOrderedByNumber(Long statementId);

    /**
     * Find all questions for a statement, ordered by order_index
     */
    /**
     * The questions of a statement in the order they are shown.
     *
     * <p>{@code COALESCE(order_index, number)} rather than plain
     * {@code order_index}: questions.order_index has been nullable since V12 and
     * V18 backfilled page_index and needs_review but not this one, so the rows
     * written before the question builder existed carry NULL. Postgres sorts
     * NULLs last, which would have put the newest question at the top of a
     * statement made entirely of legacy ones and left the rest in no order at
     * all. Falling back to number keeps those where they were.
     */
    @Query(
            "SELECT * FROM questions WHERE statement_id = :statementId AND deleted_at IS NULL "
                    + "ORDER BY COALESCE(order_index, number) ASC"
    )
    Flux<Question> findAllByStatementIdOrderedByOrderIndex(Long statementId);

    /**
     * Find all questions for a statement, ordered by order_index (Spring Data naming convention)
     */
    Flux<Question> findAllByStatementIdOrderByOrderIndex(Long statementId);

    /**
     * Find every question of a statement, trashed ones included.
     * A purge must reach previously soft-deleted questions too, otherwise they
     * are left behind still holding their own options and images.
     */
    @Query(
        "SELECT * FROM questions WHERE statement_id = :statementId ORDER BY order_index ASC"
    )
    Flux<Question> findAllByStatementIdIncludingDeleted(Long statementId);

    /**
     * Hard delete every question of a statement, trashed ones included.
     * Options and images are removed by their ON DELETE CASCADE constraints.
     */
    @Query("DELETE FROM questions WHERE statement_id = :statementId")
    Mono<Void> deleteAllByStatementId(Long statementId);

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
    Mono<Question> findByStatementIdAndNumberAndDeletedAtIsNull(
        Long statementId,
        Integer number
    );

    /**
     * Find all questions that need review
     */
    Flux<Question> findAllByNeedsReviewTrueAndDeletedAtIsNull();

    /**
     * Find all questions for a statement that need review
     */
    Flux<Question> findAllByStatementIdAndNeedsReviewTrueAndDeletedAtIsNull(
        Long statementId
    );

    /**
     * Find questions by type
     */
    Flux<Question> findAllByQuestionTypeAndDeletedAtIsNull(String questionType);

    /**
     * Find questions by type for a specific statement
     */
    Flux<Question> findAllByStatementIdAndQuestionTypeAndDeletedAtIsNull(
        Long statementId,
        String questionType
    );

    /**
     * Find multiple choice questions for a statement
     */
    @Query(
        "SELECT * FROM questions WHERE statement_id = :statementId AND question_type = 'multiple_choice' AND deleted_at IS NULL ORDER BY number ASC"
    )
    Flux<Question> findMultipleChoiceByStatementId(Long statementId);

    /**
     * Find questions with OCR confidence below threshold
     */
    @Query(
        "SELECT * FROM questions WHERE ocr_confidence < :threshold AND deleted_at IS NULL ORDER BY ocr_confidence ASC"
    )
    Flux<Question> findAllWithLowOcrConfidence(Double threshold);

    /**
     * Find questions with low OCR confidence for a specific statement
     */
    @Query(
        "SELECT * FROM questions WHERE statement_id = :statementId AND ocr_confidence < :threshold AND deleted_at IS NULL ORDER BY number ASC"
    )
    Flux<Question> findByStatementIdWithLowOcrConfidence(
        Long statementId,
        Double threshold
    );

    /**
     * Find questions on a specific page
     */
    Flux<Question> findAllByPageIndexAndDeletedAtIsNull(Integer pageIndex);

    /**
     * Find questions on a specific page for a statement
     */
    Flux<Question> findAllByStatementIdAndPageIndexAndDeletedAtIsNull(
        Long statementId,
        Integer pageIndex
    );

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
    Mono<Long> countByStatementIdAndQuestionTypeAndDeletedAtIsNull(
        Long statementId,
        String questionType
    );

    /**
     * Check if a question exists by ID and is active
     */
    Mono<Boolean> existsByIdAndDeletedAtIsNull(Long id);

    /**
     * Check if a question with the same number exists in a statement
     */
    Mono<Boolean> existsByStatementIdAndNumberAndDeletedAtIsNull(
        Long statementId,
        Integer number
    );

    /**
     * Soft delete every still-active question of a statement.
     *
     * <p>The caller supplies the exact {@code deleted_at} value it stamped on
     * the statement itself. Stamping both sides with one shared value is what
     * lets {@link #restoreAllDeletedByStatementIdAndDeletedAt(Long,
     * LocalDateTime)} restore precisely the questions this cascade touched,
     * leaving questions that were deleted individually beforehand untouched.
     */
    @Query(
        "UPDATE questions SET deleted_at = :deletedAt WHERE statement_id = :statementId AND deleted_at IS NULL"
    )
    Mono<Integer> softDeleteAllByStatementId(
        Long statementId,
        LocalDateTime deletedAt
    );

    /**
     * Undo {@link #softDeleteAllByStatementId(Long, LocalDateTime)} for the
     * questions stamped by that exact cascade, identified by the shared
     * timestamp. Questions soft deleted at any other time stay deleted.
     */
    @Query(
        "UPDATE questions SET deleted_at = NULL WHERE statement_id = :statementId AND deleted_at = :deletedAt"
    )
    Mono<Integer> restoreAllDeletedByStatementIdAndDeletedAt(
        Long statementId,
        LocalDateTime deletedAt
    );

    /**
     * Calculate total max score for a statement
     */
    @Query(
            "SELECT COALESCE(SUM(max_score), 0) FROM questions WHERE statement_id = :statementId AND deleted_at IS NULL"
    )
    Mono<Double> calculateTotalMaxScore(Long statementId);

    /**
     * The questions of a statement that have alternatives and no answer to them.
     *
     * <p>One row per question, so the caller can name them in the error instead of
     * only counting them. Both halves of the option condition matter: it has to be
     * active to count as an answer, and it has to be there at all for the question
     * to be one that needs one.
     *
     * <p>Deliberately not keyed on {@code question_type}. That column is free text
     * and three vocabularies are in circulation: the OCR paths write
     * {@code multiple_choice} and {@code development}, the exam builder writes
     * {@code multiple_choice} when a question has options, and the question CRUD
     * defaults to {@code open} because a question is created before its options
     * exist and cannot derive the type from them yet. Asking the column would
     * leave the gate watching only the writers that happen to spell it the way the
     * gate expects. Having alternatives is the thing being checked instead, which
     * is the rule {@code ManualStatementService} already applies when it picks
     * between {@code open} and {@code multiple_choice}.
     */
    @Query(
            "SELECT q.* FROM questions q "
                    + "WHERE q.statement_id = :statementId "
                    + "AND q.deleted_at IS NULL "
                    + "AND EXISTS ("
                    + "  SELECT 1 FROM question_options o "
                    + "  WHERE o.question_id = q.id AND o.deleted_at IS NULL"
                    + ") "
                    + "AND NOT EXISTS ("
                    + "  SELECT 1 FROM question_options o "
                    + "  WHERE o.question_id = q.id AND o.deleted_at IS NULL AND o.is_correct = TRUE"
                    + ") "
                    + "ORDER BY q.number ASC"
    )
    Flux<Question> findQuestionsWithoutCorrectOption(Long statementId);

    /**
     * Find the next order index for a statement
     */
    /**
     * The next display position for a statement.
     *
     * <p>Counts the soft-deleted rows too, like the option equivalent, so a
     * position is never handed out a second time and the order stays
     * deterministic across a removal. There is no unique constraint over
     * order_index — it would not even hold, since reordering renumbers only the
     * active rows — so this is tidiness rather than correctness. It is {@code
     * number} that must never come back, and that is what the constraint covers.
     */
    @Query(
        "SELECT COALESCE(MAX(order_index), 0) + 1 FROM questions WHERE statement_id = :statementId"
    )
    Mono<Integer> findNextOrderIndex(Long statementId);

    /**
     * Find the next question number for a statement.
     *
     * <p>Counts the soft-deleted rows too. uk_questions_statement_number covers
     * (statement_id, number) without deleted_at, so a deleted question keeps its
     * number for good; taking MAX over the active rows only would hand out a
     * number that is already taken and fail the insert. Numbers therefore move
     * forward and are never reused.
 */
    @Query(
        "SELECT COALESCE(MAX(number), 0) + 1 FROM questions WHERE statement_id = :statementId"
    )
    Mono<Integer> findNextQuestionNumber(Long statementId);

    /**
     * Search questions by text content (case-insensitive partial match)
     */
    @Query(
        "SELECT * FROM questions WHERE LOWER(text) LIKE LOWER(CONCAT('%', :searchTerm, '%')) AND deleted_at IS NULL ORDER BY statement_id, number"
    )
    Flux<Question> searchByText(String searchTerm);

    /**
     * Find questions with specific score range
     */
    @Query(
        "SELECT * FROM questions WHERE max_score >= :minScore AND max_score <= :maxScore AND deleted_at IS NULL ORDER BY max_score DESC"
    )
    Flux<Question> findByScoreRange(Double minScore, Double maxScore);

    /**
     * Get average OCR confidence for a statement
     */
    @Query(
        "SELECT AVG(ocr_confidence) FROM questions WHERE statement_id = :statementId AND ocr_confidence IS NOT NULL AND deleted_at IS NULL"
    )
    Mono<Double> getAverageOcrConfidence(Long statementId);
}
