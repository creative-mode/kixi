package ao.creativemode.kixi.service;

import ao.creativemode.kixi.client.OcrServiceClient;
import ao.creativemode.kixi.common.exception.ApiException;
import ao.creativemode.kixi.dto.ocr.OcrResponse;
import ao.creativemode.kixi.dto.ocr.OcrResponse.ExtractedOption;
import ao.creativemode.kixi.dto.ocr.OcrResponse.ExtractedQuestion;
import ao.creativemode.kixi.dto.ocr.OcrResponse.OcrMetadata;
import ao.creativemode.kixi.model.Question;
import ao.creativemode.kixi.model.QuestionOption;
import ao.creativemode.kixi.model.Statement;
import ao.creativemode.kixi.repository.QuestionOptionRepository;
import ao.creativemode.kixi.repository.QuestionRepository;
import ao.creativemode.kixi.repository.StatementRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service for managing Statement entities and OCR integration.
 *
 * Provides business logic for:
 * - CRUD operations on statements
 * - OCR-based statement creation from images
 * - Mapping OCR results to domain entities
 * - Managing questions and options
 *
 * For full OCR processing with entity lookup/creation, use OcrPersistenceService.
 */
@Service
public class StatementService {

    private static final Logger log = LoggerFactory.getLogger(
        StatementService.class
    );

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
        OcrServiceClient ocrServiceClient
    ) {
        this.statementRepository = statementRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.ocrServiceClient = ocrServiceClient;
    }

    // =========================================================================
    // OCR Integration (Legacy - for simple cases without entity lookup)
    // =========================================================================

    /**
     * Create a statement from uploaded images using OCR.
     *
     * Note: For full entity lookup/creation (SchoolYear, Course, Subject, Class),
     * use OcrPersistenceService.processAndPersist() instead.
     *
     * @param files List of uploaded image files
     * @param createdBy ID of the user creating the statement
     * @return Mono containing the created statement with questions
     */
    @Transactional
    public Mono<StatementWithQuestions> createFromOcr(
        List<FilePart> files,
        Long createdBy
    ) {
        log.info(
            "Creating statement from OCR: {} file(s), createdBy={}",
            files.size(),
            createdBy
        );

        return ocrServiceClient
            .extractText(files)
            .flatMap(ocrResponse -> {
                if (ocrResponse.isError()) {
                    log.error(
                        "OCR extraction failed: {}",
                        ocrResponse.errorMessage()
                    );
                    return Mono.error(
                        ApiException.badRequest(
                            "OCR extraction failed: " +
                                ocrResponse.errorMessage()
                        )
                    );
                }

                log.info(
                    "OCR extraction successful: requestId={}, confidence={}, questions={}",
                    ocrResponse.requestId(),
                    ocrResponse.overallConfidence(),
                    ocrResponse.questions() != null
                        ? ocrResponse.questions().size()
                        : 0
                );

                return createStatementFromOcrResponse(ocrResponse, createdBy);
            })
            .doOnSuccess(result ->
                log.info(
                    "Statement created from OCR: statementId={}, questions={}",
                    result.statement().getId(),
                    result.questions().size()
                )
            )
            .doOnError(error ->
                log.error("Failed to create statement from OCR", error)
            );
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
        Long createdBy
    ) {
        // Create and populate statement from metadata
        Statement statement = mapMetadataToStatement(
            ocrResponse.metadata(),
            ocrResponse
        );
        statement.setCreatedBy(createdBy);
        statement.setOcrMetadata(
            ocrResponse.requestId(),
            ocrResponse.overallConfidence(),
            ocrResponse.needsReview()
        );
        statement.setSource("ocr");

        // Calculate total max score from questions
        if (ocrResponse.questions() != null) {
            double totalScore = ocrResponse
                .questions()
                .stream()
                .filter(q -> q.getCotacaoValue() != null)
                .mapToDouble(ExtractedQuestion::getCotacaoValue)
                .sum();
            if (totalScore > 0) {
                statement.setTotalMaxScore(totalScore);
            }
        }

        // Save statement first
        return statementRepository
            .save(statement)
            .flatMap(savedStatement -> {
                if (
                    ocrResponse.questions() == null ||
                    ocrResponse.questions().isEmpty()
                ) {
                    return Mono.just(
                        new StatementWithQuestions(
                            savedStatement,
                            List.of(),
                            List.of()
                        )
                    );
                }

                // Create and save questions
                return createQuestionsFromOcr(
                    savedStatement.getId(),
                    ocrResponse.questions()
                )
                    .collectList()
                    .flatMap(savedQuestions -> {
                        // Collect all question IDs
                        List<Long> questionIds = savedQuestions
                            .stream()
                            .map(Question::getId)
                            .toList();

                        // Load all options for these questions
                        return optionRepository
                            .findAllByQuestionIds(questionIds)
                            .collectList()
                            .map(options ->
                                new StatementWithQuestions(
                                    savedStatement,
                                    savedQuestions,
                                    options
                                )
                            );
                    });
            });
    }

    /**
     * Map OCR metadata to Statement entity.
     */
    private Statement mapMetadataToStatement(
        OcrMetadata metadata,
        OcrResponse ocrResponse
    ) {
        Statement statement = new Statement();

        if (metadata != null) {
            // Title
            if (metadata.title() != null && metadata.title().value() != null) {
                statement.setTitle(metadata.title().value());
            } else {
                statement.setTitle(
                    "Imported Statement - " + LocalDateTime.now()
                );
            }

            // Exam type
            if (
                metadata.examType() != null &&
                metadata.examType().value() != null
            ) {
                statement.setExamType(metadata.examType().value());
            } else {
                statement.setExamType("Prova de Exame");
            }

            // Duration
            if (
                metadata.durationMinutes() != null &&
                metadata.durationMinutes().value() != null
            ) {
                statement.setDurationMinutes(
                    metadata.durationMinutes().value()
                );
            }

            // Variant
            if (
                metadata.variant() != null && metadata.variant().value() != null
            ) {
                statement.setVariant(metadata.variant().value());
            }

            // Instructions
            if (
                metadata.instructions() != null &&
                metadata.instructions().value() != null
            ) {
                statement.setInstructions(metadata.instructions().value());
            }

            // Total max score from metadata
            if (metadata.getTotalMaxScoreValue() != null) {
                statement.setTotalMaxScore(metadata.getTotalMaxScoreValue());
            }

            // Note: schoolYearId, termId, subjectId, classId, courseId
            // are resolved in OcrPersistenceService which does the full lookup
        }

        // Set OCR-specific fields
        statement.setVisible(false); // Require manual review before publishing
        statement.setNeedsReview(
            ocrResponse.needsReview() ||
                (ocrResponse.overallConfidence() != null &&
                    ocrResponse.overallConfidence() < LOW_CONFIDENCE_THRESHOLD)
        );

        return statement;
    }

    /**
     * Create questions from OCR extracted questions.
     */
    private Flux<Question> createQuestionsFromOcr(
        Long statementId,
        List<ExtractedQuestion> extractedQuestions
    ) {
        return Flux.fromIterable(extractedQuestions)
            .index()
            .flatMap(tuple -> {
                int index = tuple.getT1().intValue();
                ExtractedQuestion extracted = tuple.getT2();

                Question question = mapExtractedToQuestion(
                    statementId,
                    extracted,
                    index
                );

                return questionRepository
                    .save(question)
                    .flatMap(savedQuestion -> {
                        // Create options if this is a multiple choice question
                        if (
                            extracted.options() != null &&
                            !extracted.options().isEmpty()
                        ) {
                            return createOptionsFromOcr(
                                savedQuestion.getId(),
                                extracted.options()
                            ).then(Mono.just(savedQuestion));
                        }
                        return Mono.just(savedQuestion);
                    });
            });
    }

    /**
     * Map extracted question to Question entity.
     */
    private Question mapExtractedToQuestion(
        Long statementId,
        ExtractedQuestion extracted,
        int orderIndex
    ) {
        Question question = new Question();
        question.setStatementId(statementId);

        // Parse number (might be string like "1", "2a", etc.)
        try {
            String numStr =
                extracted.number() != null
                    ? extracted.number().replaceAll("[^0-9]", "")
                    : "";
            question.setNumber(
                numStr.isEmpty() ? orderIndex + 1 : Integer.parseInt(numStr)
            );
        } catch (NumberFormatException e) {
            question.setNumber(orderIndex + 1);
        }

        question.setOrderIndex(orderIndex);

        // Text
        question.setText(extracted.getTextValue());

        // Question type - map from Portuguese to database format
        String type = extracted.getTypeValue();
        question.setQuestionType(mapQuestionType(type));

        // Cotação (max score)
        if (extracted.getCotacaoValue() != null) {
            question.setMaxScore(extracted.getCotacaoValue());
        }

        // OCR metadata
        question.setOcrConfidence(extracted.confidence());
        question.setPageIndex(extracted.pageIndex());

        // Mark for review if low confidence
        question.setNeedsReview(
            extracted.confidence() != null &&
                extracted.confidence() < LOW_CONFIDENCE_THRESHOLD
        );

        return question;
    }

    /**
     * Map question type from Portuguese to database format.
     */
    private String mapQuestionType(String type) {
        if (type == null) {
            return "unknown";
        }
        return switch (type.toLowerCase()) {
            case "dissertativa" -> "development";
            case "multipla_escolha" -> "multiple_choice";
            default -> type;
        };
    }

    /**
     * Create options from OCR extracted options.
     */
    private Flux<QuestionOption> createOptionsFromOcr(
        Long questionId,
        List<ExtractedOption> extractedOptions
    ) {
        return Flux.fromIterable(extractedOptions)
            .index()
            .flatMap(tuple -> {
                int index = tuple.getT1().intValue();
                ExtractedOption extracted = tuple.getT2();

                QuestionOption option = new QuestionOption();
                option.setQuestionId(questionId);
                option.setOptionLabel(extracted.optionLabel());
                option.setOptionText(extracted.optionText());
                option.setOrderIndex(index);
                option.setOcrConfidence(extracted.confidence());
                option.setIsCorrect(false); // OCR cannot determine correct answer

                return optionRepository.save(option);
            });
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
     * Find a statement with its questions.
     */
    public Mono<StatementWithQuestions> findByIdWithQuestions(Long id) {
        return findById(id).flatMap(statement ->
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
     * Find statements by subject.
     */
    public Flux<Statement> findBySubject(Long subjectId) {
        return statementRepository.findAllBySubjectIdAndDeletedAtIsNull(
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
        return statementRepository
            .findByIdAndDeletedAtIsNotNull(id)
            .switchIfEmpty(
                Mono.error(
                    ApiException.notFound("Deleted statement not found: " + id)
                )
            )
            .flatMap(statement -> {
                statement.restore();
                return statementRepository.save(statement);
            })
            .then();
    }

    /**
     * Hard delete a statement and its questions/options.
     */
    @Transactional
    public Mono<Void> hardDelete(Long id) {
        return findById(id).flatMap(statement ->
            questionRepository
                .findAllByStatementIdOrderByOrderIndex(statement.getId())
                .flatMap(question ->
                    optionRepository
                        .softDeleteAllByQuestionId(question.getId())
                        .then(questionRepository.delete(question))
                )
                .then(statementRepository.delete(statement))
        );
    }

    /**
     * Approve a statement review.
     */
    @Transactional
    public Mono<Statement> approveReview(Long id) {
        return findById(id).flatMap(statement -> {
            statement.approveReview();
            return statementRepository.save(statement);
        });
    }

    /**
     * Set statement visibility.
     */
    @Transactional
    public Mono<Statement> setVisible(Long id, boolean visible) {
        return findById(id).flatMap(statement -> {
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
    // Result Records
    // =========================================================================

    /**
     * Statement with its questions and options.
     */
    public record StatementWithQuestions(
        Statement statement,
        List<Question> questions,
        List<QuestionOption> options
    ) {}
}
