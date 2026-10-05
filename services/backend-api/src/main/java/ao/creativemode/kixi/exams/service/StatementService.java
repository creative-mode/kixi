package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.exams.model.Question;
import ao.creativemode.kixi.exams.model.Statement;
import ao.creativemode.kixi.exams.repository.QuestionOptionRepository;
import ao.creativemode.kixi.exams.repository.QuestionRepository;
import ao.creativemode.kixi.exams.repository.StatementRepository;
import ao.creativemode.kixi.institutions.service.InstitutionAccessService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import java.time.LocalDateTime;
import reactor.core.publisher.Mono;

/**
 * Service for managing Statement entities: CRUD, lifecycle (soft delete,
 * restore), review and visibility, and the statistics counters.
 *
 * OCR-based creation lives elsewhere: LegacyOcrStatementService for the
 * simple case, OcrPersistenceService for full entity lookup/creation.
 */
@Service
public class StatementService {

    private final StatementRepository statementRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final InstitutionAccessService accessService;

    public StatementService(
        StatementRepository statementRepository,
        QuestionRepository questionRepository,
        QuestionOptionRepository optionRepository,
        InstitutionAccessService accessService
    ) {
        this.statementRepository = statementRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.accessService = accessService;
    }

    // =========================================================================
    // CRUD Operations
    // =========================================================================

    /**
     * Find all active (non-deleted) statements.
     */
    public Flux<Statement> findAllActive() {
        return statementRepository.findAllByDeletedAtIsNull();
    }

    /**
     * Find all visible active statements for student-facing reads.
     */
    public Flux<Statement> findAllVisible() {
        return statementRepository.findAllByVisibleTrueAndDeletedAtIsNull();
    }

    /**
     * Find all soft-deleted statements.
     */
    public Flux<Statement> findAllDeleted() {
        return statementRepository.findAllByDeletedAtIsNotNull();
    }

    /**
     * Find a statement by ID.
     */
    public Mono<Statement> findById(Long id) {
        return statementRepository
            .findByIdAndDeletedAtIsNull(id)
            .switchIfEmpty(
                Mono.error(ApiException.notFound("Statement not found: " + id))
            );
    }

    /**
     * Find a visible active statement by ID.
     */
    public Mono<Statement> findByIdVisible(Long id) {
        return statementRepository
            .findByIdAndVisibleTrueAndDeletedAtIsNull(id)
            .switchIfEmpty(
                Mono.error(ApiException.notFound("Statement not found: " + id))
            );
    }

    /**
     * Find a statement with its questions.
     */
    public Mono<StatementWithQuestions> findByIdWithQuestions(Long id) {
        return findByIdWithQuestions(findById(id));
    }

    /**
     * Find a visible statement with its questions.
     */
    public Mono<StatementWithQuestions> findByIdWithQuestionsVisible(Long id) {
        return findByIdWithQuestions(findByIdVisible(id));
    }

    private Mono<StatementWithQuestions> findByIdWithQuestions(Mono<Statement> statementMono) {
        return statementMono.flatMap(statement ->
            questionRepository
                .findAllByStatementIdOrderByOrderIndex(statement.getId())
                .collectList()
                .flatMap(questions -> {
                    if (questions.isEmpty()) {
                        return Mono.just(
                            new StatementWithQuestions(
                                statement,
                                List.of(),
                                List.of()
                            )
                        );
                    }

                    List<Long> questionIds = questions
                        .stream()
                        .map(Question::getId)
                        .toList();

                    return optionRepository
                        .findAllByQuestionIds(questionIds)
                        .collectList()
                        .map(options ->
                            new StatementWithQuestions(
                                statement,
                                questions,
                                options
                            )
                        );
                })
        );
    }

    /**
     * Find statements needing review.
     */
    public Flux<Statement> findNeedingReview() {
        return statementRepository.findAllByNeedsReviewTrueAndDeletedAtIsNull();
    }

    /**
     * Find statements created via OCR.
     */
    public Flux<Statement> findFromOcr() {
        return statementRepository.findAllFromOcr();
    }

    /**
     * Find statements by school year.
     */
    public Flux<Statement> findBySchoolYear(Long schoolYearId) {
        return statementRepository.findAllBySchoolYearIdAndDeletedAtIsNull(
            schoolYearId
        );
    }

    /**
     * Find visible statements by school year.
     */
    public Flux<Statement> findBySchoolYearVisible(Long schoolYearId) {
        return statementRepository.findAllByVisibleTrueAndSchoolYearIdAndDeletedAtIsNull(
            schoolYearId
        );
    }

    /**
     * Find statements by subject.
     */
    public Flux<Statement> findBySubject(Long subjectId) {
        return statementRepository.findAllBySubjectIdAndDeletedAtIsNull(
            subjectId
        );
    }

    /**
     * Find visible statements by subject.
     */
    public Flux<Statement> findBySubjectVisible(Long subjectId) {
        return statementRepository.findAllByVisibleTrueAndSubjectIdAndDeletedAtIsNull(
            subjectId
        );
    }

    /**
     * Search statements by title.
     */
    public Flux<Statement> searchByTitle(String searchTerm) {
        return statementRepository.searchByTitle(searchTerm);
    }

    /**
     * Search only visible active statements by title.
     */
    public Flux<Statement> searchByTitleVisible(String searchTerm) {
        return statementRepository.searchVisibleByTitle(searchTerm);
    }

    /**
     * Save a statement.
     */
    public Mono<Statement> save(Statement statement) {
        return statementRepository.save(statement);
    }

    /**
     * Soft delete a statement and, with it, its still-active questions.
     *
     * <p>Soft deleting only the statement used to leave the questions active:
     * they kept showing up in the question listings while their parent was in
     * the trash. The cascade stamps both the statement and its questions with
     * the same {@code deletedAt} value so the restore below can undo exactly
     * this cascade without reviving questions that were deleted individually
     * beforehand.
     */
    @Transactional
    public Mono<Void> softDelete(Long id, Long accountId, boolean admin) {
        return findById(id)
            .flatMap(statement -> requireCanEdit(statement, accountId, admin))
            .flatMap(statement -> {
                LocalDateTime deletedAt = LocalDateTime.now();
                statement.setDeletedAt(deletedAt);
                return statementRepository.save(statement)
                    .then(Mono.defer(() ->
                        questionRepository.softDeleteAllByStatementId(id, deletedAt)))
                    .then();
            });
    }

    /**
     * Restore a soft-deleted statement together with the questions that its
     * own soft delete had cascaded.
     */
    @Transactional
    public Mono<Void> restore(Long id) {
        return statementRepository
            .findByIdAndDeletedAtIsNotNull(id)
            .switchIfEmpty(
                Mono.error(
                    ApiException.notFound("Deleted statement not found: " + id)
                )
            )
            .flatMap(statement -> {
                LocalDateTime deletedAt = statement.getDeletedAt();
                statement.restore();
                return statementRepository.save(statement)
                    .then(Mono.defer(() ->
                        questionRepository.restoreAllDeletedByStatementIdAndDeletedAt(
                            id, deletedAt)))
                    .then();
            });
    }

    /**
     * Hard delete a statement and its questions/options.
     */
    @Transactional
    public Mono<Void> hardDelete(Long id) {
        // A purge is only allowed on a trashed statement, so the lookup must
        // filter on deleted_at IS NOT NULL. Using findById (active only) made
        // every purge attempt fail with 404 and left the cleanup unreachable.
        return statementRepository
            .findByIdAndDeletedAtIsNotNull(id)
            .switchIfEmpty(
                Mono.error(
                    ApiException.notFound("Trashed statement not found: " + id)
                )
            )
            .flatMap(statement ->
                // Options and images go with their question through the
                // ON DELETE CASCADE constraints, so deleting the questions is
                // enough. Soft-deleting the options first was pointless: the
                // cascade removes those rows anyway.
                questionRepository
                    .deleteAllByStatementId(statement.getId())
                    .then(statementRepository.delete(statement))
            )
            .then();
    }

    /**
     * Approve a statement review, making it visible.
     */
    @Transactional
    public Mono<Statement> approveReview(Long id, Long accountId, boolean admin) {
        return findById(id)
            .flatMap(statement -> requireCanEdit(statement, accountId, admin))
            .flatMap(statement -> {
                statement.approveReview();
                return statementRepository.save(statement);
            });
    }

    /**
     * Set statement visibility.
     */
    @Transactional
    public Mono<Statement> setVisible(Long id, boolean visible, Long accountId, boolean admin) {
        return findById(id)
            .flatMap(statement -> requireCanEdit(statement, accountId, admin))
            .flatMap(statement -> {
                statement.setVisible(visible);
                return statementRepository.save(statement);
            });
    }

    // =========================================================================
    // Statistics
    // =========================================================================

    /**
     * Count active statements.
     */
    public Mono<Long> countActive() {
        return statementRepository.countByDeletedAtIsNull();
    }

    /**
     * Count statements needing review.
     */
    public Mono<Long> countNeedingReview() {
        return statementRepository.countByNeedsReviewTrueAndDeletedAtIsNull();
    }

    /**
     * Count statements by source.
     */
    public Mono<Long> countBySource(String source) {
        return statementRepository.countBySourceAndDeletedAtIsNull(source);
    }

    // =========================================================================
    // Authorization
    // =========================================================================

    /**
     * Whether the account may change this statement: an administrator may do
     * anything, a teacher only the statements of the classes and subjects they
     * were assigned to.
     */
    private Mono<Statement> requireCanEdit(Statement statement, Long accountId, boolean admin) {
        if (statement.getInstitutionId() == null) {
            // Statements created before the institution model (the OCR flows)
            // carry no school, so there is nothing to check them against.
            // Backfilling them is a separate concern from this rule.
            return Mono.just(statement);
        }
        return accessService
            .requireCanAuthor(
                accountId,
                admin,
                statement.getInstitutionId(),
                statement.getSubjectId(),
                statement.getClassId()
            )
            .thenReturn(statement);
    }
}
