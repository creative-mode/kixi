package ao.creativemode.kixi.exams.service;

import ao.creativemode.kixi.shared.exception.ApiException;
import ao.creativemode.kixi.exams.dto.statement.StatementRequest;
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
    private final StatementLinkValidationService validator;
    private final StatementWriteAccessService writeAccess;

    public StatementService(
        StatementRepository statementRepository,
        QuestionRepository questionRepository,
        QuestionOptionRepository optionRepository,
        InstitutionAccessService accessService,
        StatementLinkValidationService validator,
        StatementWriteAccessService writeAccess
    ) {
        this.statementRepository = statementRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.accessService = accessService;
        this.validator = validator;
        this.writeAccess = writeAccess;
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
    public Mono<Void> restore(Long id, Long accountId, boolean admin) {
        return statementRepository
            .findByIdAndDeletedAtIsNotNull(id)
            .switchIfEmpty(
                Mono.error(
                    ApiException.notFound("Deleted statement not found: " + id)
                )
            )
            .flatMap(statement -> requireCanEdit(statement, accountId, admin))
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
    public Mono<Void> hardDelete(Long id, Long accountId, boolean admin) {
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
            .flatMap(statement -> requireCanEdit(statement, accountId, admin))
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

    /**
     * Correct the metadata of a statement and nothing else.
     *
     * <p>The questions and their options are deliberately left untouched: on a
     * statement the OCR produced they are what the teacher is correcting, and a
     * full replace would throw them away. For the same reason this does not
     * touch {@code visible} or {@code needsReview}, which are what
     * {@link #approveReview} and {@link #setVisible} are for, and it keeps
     * {@code source} as the record of where the statement came from.
     *
     * <p>{@code totalMaxScore} is taken from the caller rather than derived, which
     * is the one place where leaving the questions alone shows: on creation the
     * total is summed from them, so an edit can leave a statement whose declared
     * total no longer matches its questions. That is the price of letting a
     * teacher correct a score the OCR read off a scan, and it is why the field is
     * accepted here at all.
     *
     * <p>Everything else is a replace, including the fields left out of the
     * request: this is a PUT, so omitting {@code schoolYearId} clears it — which
     * also switches off the class-to-year check, since a null year gives the
     * validator nothing to compare against.
     *
     * <p>Authorisation is checked twice, and both halves matter. Against the
     * statement as it stands, so a teacher cannot pull another teacher's paper
     * into their own class; and against the metadata being written, so they
     * cannot hand a paper over to a class they do not teach.
     *
     * <p>A statement the OCR produced carries no institution, so the first check
     * has nothing to weigh against and it is the second one that admits it into
     * a school.
     */
    @Transactional
    public Mono<Statement> update(Long id, StatementRequest request, Long accountId, boolean admin) {
        return validator.requireAClassForTeachers(request.classId(), admin)
            .then(Mono.defer(() -> findById(id)))
            .flatMap(statement -> requireCanEdit(statement, accountId, admin).thenReturn(statement))
            .flatMap(statement -> requireCanWriteAs(request, accountId, admin).thenReturn(statement))
            .flatMap(statement -> validator.validate(
                    request.schoolYearId(),
                    request.termId(),
                    request.classId(),
                    request.subjectId(),
                    request.courseId())
                .thenReturn(statement))
            .flatMap(statement -> {
                apply(statement, request);
                return statementRepository.save(statement);
            });
    }

    /** Whether the account may point a statement at these references. */
    private Mono<Void> requireCanWriteAs(StatementRequest request, Long accountId, boolean admin) {
        return Mono.defer(() -> accessService.requireCanAuthor(
            accountId, admin, request.institutionId(), request.subjectId(), request.classId()));
    }

    private void apply(Statement statement, StatementRequest request) {
        statement.setInstitutionId(request.institutionId());
        statement.setSubjectId(request.subjectId());
        statement.setTitle(request.title().trim());
        statement.setExamType(request.examType().trim());
        statement.setDurationMinutes(request.durationMinutes());
        statement.setVariant(blankToNull(request.variant()));
        statement.setInstructions(blankToNull(request.instructions()));
        statement.setTotalMaxScore(request.totalMaxScore());
        statement.setSchoolYearId(request.schoolYearId());
        statement.setTermId(request.termId());
        statement.setClassId(request.classId());
        statement.setCourseId(request.courseId());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    /** The statement-scoped write rule, shared with the question and option services. */
    private Mono<Statement> requireCanEdit(Statement statement, Long accountId, boolean admin) {
        return writeAccess.checkCanWrite(statement, accountId, admin);
    }
}
