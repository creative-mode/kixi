package ao.creativemode.kixi.service;

import ao.creativemode.kixi.client.OcrServiceClient;
import ao.creativemode.kixi.dto.ocr.OcrResponse;
import ao.creativemode.kixi.dto.ocr.OcrResponse.ExtractedQuestion;
import ao.creativemode.kixi.dto.ocr.OcrResponse.ExtractedOption;
import ao.creativemode.kixi.dto.ocr.OcrResponse.OcrMetadata;
import ao.creativemode.kixi.model.Statement;
import ao.creativemode.kixi.model.Question;
import ao.creativemode.kixi.model.QuestionOption;
import ao.creativemode.kixi.repository.StatementRepository;
import ao.creativemode.kixi.repository.QuestionRepository;
import ao.creativemode.kixi.repository.QuestionOptionRepository;
import ao.creativemode.kixi.common.exception.ApiException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Service for managing Statement entities and OCR integration.
 *
 * Provides business logic for:
 * - CRUD operations on statements
 * - OCR-based statement creation from images
 * - Mapping OCR results to domain entities
 * - Managing questions and options
 */
@Service
public class StatementService {

    private static final Logger log = LoggerFactory.getLogger(StatementService.class);

    private static final double LOW_CONFIDENCE_THRESHOLD = 0.8;
    private static final double MIN_CONFIDENCE_THRESHOLD = 0.5;

    private final StatementRepository statementRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final OcrServiceClient ocrServiceClient;

    public StatementService(
            StatementRepository statementRepository,
            QuestionRepository questionRepository,
            QuestionOptionRepository optionRepository,
            OcrServiceClient ocrServiceClient) {
        this.statementRepository = statementRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.ocrServiceClient = ocrServiceClient;
    }

    // =========================================================================
    // OCR Integration
    // =========================================================================

    /**
     * Create a statement from uploaded images using OCR.
     *
     * @param files List of uploaded image files
     * @param createdBy ID of the user creating the statement
     * @return Mono containing the created statement with questions
     */
    @Transactional
    public Mono<StatementWithQuestions> createFromOcr(List<FilePart> files, Long createdBy) {
        log.info("Creating statement from OCR: {} file(s), createdBy={}", files.size(), createdBy);

        return ocrServiceClient.extractText(files)
                .flatMap(ocrResponse -> {
                    if (ocrResponse.isError()) {
                        log.error("OCR extraction failed: {}", ocrResponse.errorMessage());
                        return Mono.error(ApiException.badRequest(
                                "OCR extraction failed: " + ocrResponse.errorMessage()));
                    }

                    log.info("OCR extraction successful: requestId={}, confidence={}, questions={}",
                            ocrResponse.requestId(),
                            ocrResponse.overallConfidence(),
                            ocrResponse.questions() != null ? ocrResponse.questions().size() : 0);

                    return createStatementFromOcrResponse(ocrResponse, createdBy);
                })
                .doOnSuccess(result -> log.info(
                        "Statement created from OCR: statementId={}, questions={}",
                        result.statement().getId(),
                        result.questions().size()))
                .doOnError(error -> log.error("Failed to create statement from OCR", error));
    }

    /**
     * Create a statement from an OCR response.
     *
     * @param ocrResponse The OCR response containing extracted data
     * @param createdBy ID of the user creating the statement
     * @return Mono containing the created statement with questions
     */
    @Transactional
    public Mono<StatementWithQuestions> createStatementFromOcrResponse(
            OcrResponse ocrResponse,
            Long createdBy) {

        // Create and populate statement from metadata
        Statement statement = mapMetadataToStatement(ocrResponse.metadata(), ocrResponse);
        statement.setCreatedBy(createdBy);
        statement.setOcrMetadata(
                ocrResponse.requestId(),
                ocrResponse.overallConfidence(),
                ocrResponse.needsReview());
        statement.setSource("ocr");

        // Calculate total max score from questions
        if (ocrResponse.questions() != null) {
            double totalScore = ocrResponse.questions().stream()
                    .filter(q -> q.maxScore() != null && q.maxScore().value() != null)
                    .mapToDouble(q -> q.maxScore().value())
                    .sum();
            statement.setTotalMaxScore(totalScore);
        }

        // Save statement first
        return statementRepository.save(statement)
                .flatMap(savedStatement -> {
                    if (ocrResponse.questions() == null || ocrResponse.questions().isEmpty()) {
                        return Mono.just(new StatementWithQuestions(
                                savedStatement, List.of(), List.of()));
                    }

                    // Create and save questions
                    return createQuestionsFromOcr(savedStatement.getId(), ocrResponse.questions())
                            .collectList()
                            .flatMap(savedQuestions -> {
                                // Collect all question IDs
                                List<Long> questionIds = savedQuestions.stream()
                                        .map(Question::getId)
                                        .toList();

                                // Load all options for these questions
                                return optionRepository.findAllByQuestionIds(questionIds)
                                        .collectList()
                                        .map(options -> new StatementWithQuestions(
                                                savedStatement, savedQuestions, options));
                            });
                });
    }

    /**
     * Map OCR metadata to Statement entity.
     */
    private Statement mapMetadataToStatement(OcrMetadata metadata, OcrResponse ocrResponse) {
        Statement statement = new Statement();

        if (metadata != null) {
            // Title
            if (metadata.title() != null && metadata.title().value() != null) {
                statement.setTitle(metadata.title().value());
            } else {
                statement.setTitle("Imported Statement - " + LocalDateTime.now());
            }

            // Exam type
            if (metadata.examType() != null && metadata.examType().value() != null) {
                statement.setExamType(metadata.examType().value());
            }

            // Duration
            if (metadata.durationMinutes() != null && metadata.durationMinutes().value() != null) {
                statement.setDurationMinutes(metadata.durationMinutes().value());
            }

            // Variant
            if (metadata.variant() != null && metadata.variant().value() != null) {
                statement.setVariant(metadata.variant().value());
            }

            // Instructions
            if (metadata.instructions() != null && metadata.instructions().value() != null) {
                statement.setInstructions(metadata.instructions().value());
            }

            // Note: schoolYearId, termId, subjectId, classId, courseId need to be
            // resolved from the text values (e.g., "2024/2025" -> ID lookup)
            // This would require additional repositories and lookup logic
            // For now, these are left null and can be set manually or via a separate endpoint
        }

        // Set OCR-specific fields
        statement.setVisible(false); // Require manual review before publishing
        statement.setNeedsReview(ocrResponse.needsReview() ||
                (ocrResponse.overallConfidence() != null &&
                        ocrResponse.overallConfidence() < LOW_CONFIDENCE_THRESHOLD));

        return statement;
    }

    /**
     * Create questions from OCR extracted questions.
     */
    private Flux<Question> createQuestionsFromOcr(Long statementId, List<ExtractedQuestion> extractedQuestions) {
        return Flux.fromIterable(extractedQuestions)
                .flatMap(extracted -> {
                    Question question = mapExtractedToQuestion(statementId, extracted);
                    return questionRepository.save(question)
                            .flatMap(savedQuestion -> {
                                // Create options if this is a multiple choice question
                                if (extracted.options() != null && !extracted.options().isEmpty()) {
                                    return createOptionsFromOcr(savedQuestion.getId(), extracted.options())
                                            .then(Mono.just(savedQuestion));
                                }
                                return Mono.just(savedQuestion);
                            });
                });
    }

    /**
     * Map extracted question to Question entity.
     */
    private Question mapExtractedToQuestion(Long statementId, ExtractedQuestion extracted) {
        Question question = new Question();
        question.setStatementId(statementId);
        question.setNumber(extracted.number());
        question.setOrderIndex(extracted.number());

        // Text
        if (extracted.text() != null && extracted.text().value() != null) {
            question.setText(extracted.text().value());
        }

        // Question type
        question.setQuestionType(extracted.getQuestionTypeValue());

        // Max score
        if (extracted.maxScore() != null && extracted.maxScore().value() != null) {
            question.setMaxScore(extracted.maxScore().value());
        }

        // OCR metadata
        question.setOcrConfidence(extracted.confidence());
        question.setPageIndex(extracted.pageIndex());

        // Mark for review if low confidence
        question.setNeedsReview(extracted.confidence() != null &&
                extracted.confidence() < LOW_CONFIDENCE_THRESHOLD);

        return question;
    }

    /**
     * Create options from OCR extracted options.
     */
    private Flux<QuestionOption> createOptionsFromOcr(Long questionId, List<ExtractedOption> extractedOptions) {
        return Flux.fromIterable(extractedOptions)
                .index()
                .flatMap(indexed -> {
                    ExtractedOption extracted = indexed.getT2();
                    int index = indexed.getT1().intValue();

                    QuestionOption option = new QuestionOption();
                    option.setQuestionId(questionId);
                    option.setOptionLabel(extracted.optionLabel());
                    option.setOptionText(extracted.optionText());
                    option.setIsCorrect(false); // OCR doesn't know the correct answer
                    option.setOrderIndex(index);
                    option.setOcrConfidence(extracted.confidence());

                    return optionRepository.save(option);
                });
    }

    // =========================================================================
    // Standard CRUD Operations
    // =========================================================================

    /**
     * Find all active statements.
     */
    public Flux<Statement> findAllActive() {
        return statementRepository.findAllByDeletedAtIsNull();
    }

    /**
     * Find all deleted statements.
     */
    public Flux<Statement> findAllDeleted() {
        return statementRepository.findAllByDeletedAtIsNotNull();
    }

    /**
     * Find a statement by ID.
     */
    public Mono<Statement> findById(Long id) {
        return statementRepository.findByIdAndDeletedAtIsNull(id)
                .switchIfEmpty(Mono.error(ApiException.notFound("Statement not found")));
    }

    /**
     * Find a statement with all its questions.
     */
    public Mono<StatementWithQuestions> findByIdWithQuestions(Long id) {
        return findById(id)
                .flatMap(statement ->
                        questionRepository.findAllByStatementIdOrderedByNumber(id)
                                .collectList()
                                .flatMap(questions -> {
                                    List<Long> questionIds = questions.stream()
                                            .map(Question::getId)
                                            .toList();

                                    if (questionIds.isEmpty()) {
                                        return Mono.just(new StatementWithQuestions(
                                                statement, questions, List.of()));
                                    }

                                    return optionRepository.findAllByQuestionIds(questionIds)
                                            .collectList()
                                            .map(options -> new StatementWithQuestions(
                                                    statement, questions, options));
                                })
                );
    }

    /**
     * Find statements that need review.
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
        return statementRepository.findAllBySchoolYearIdAndDeletedAtIsNull(schoolYearId);
    }

    /**
     * Find statements by subject.
     */
    public Flux<Statement> findBySubject(Long subjectId) {
        return statementRepository.findAllBySubjectIdAndDeletedAtIsNull(subjectId);
    }

    /**
     * Search statements by title.
     */
    public Flux<Statement> searchByTitle(String searchTerm) {
        return statementRepository.searchByTitle(searchTerm);
    }

    /**
     * Save a statement.
     */
    public Mono<Statement> save(Statement statement) {
        return statementRepository.save(statement);
    }

    /**
     * Soft delete a statement.
     */
    @Transactional
    public Mono<Void> softDelete(Long id) {
        return findById(id)
                .flatMap(statement -> {
                    statement.markAsDeleted();
                    return statementRepository.save(statement);
                })
                .then();
    }

    /**
     * Restore a soft-deleted statement.
     */
    @Transactional
    public Mono<Void> restore(Long id) {
        return statementRepository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest("Statement is not deleted")))
                .flatMap(statement -> {
                    statement.restore();
                    return statementRepository.save(statement);
                })
                .then();
    }

    /**
     * Hard delete a statement (only if already soft-deleted).
     */
    @Transactional
    public Mono<Void> hardDelete(Long id) {
        return statementRepository.findByIdAndDeletedAtIsNotNull(id)
                .switchIfEmpty(Mono.error(ApiException.badRequest(
                        "Only deleted statements can be permanently removed")))
                .flatMap(statement ->
                        // First delete all questions and options
                        questionRepository.findAllByStatementIdAndDeletedAtIsNull(id)
                                .flatMap(question ->
                                        optionRepository.softDeleteAllByQuestionId(question.getId())
                                                .then(questionRepository.delete(question)))
                                .then(statementRepository.delete(statement)))
                .then();
    }

    /**
     * Approve review for a statement (mark as reviewed).
     */
    @Transactional
    public Mono<Statement> approveReview(Long id) {
        return findById(id)
                .flatMap(statement -> {
                    statement.approveReview();
                    statement.setVisible(true);
                    return statementRepository.save(statement);
                });
    }

    /**
     * Mark a statement as visible.
     */
    public Mono<Statement> setVisible(Long id, boolean visible) {
        return findById(id)
                .flatMap(statement -> {
                    statement.setVisible(visible);
                    return statementRepository.save(statement);
                });
    }

    // =========================================================================
    // Statistics
    // =========================================================================

    /**
     * Count all active statements.
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
    // DTOs
    // =========================================================================

    /**
     * Record representing a statement with its questions and options.
     */
    public record StatementWithQuestions(
            Statement statement,
            List<Question> questions,
            List<QuestionOption> options
    ) {}
}
